package org.multipaz.wallet.provisioning.remote

import android.content.Context
import org.multipaz.securearea.AndroidKeystoreSecureArea
import org.multipaz.cbor.DataItem
import org.multipaz.device.AssertionNonce
import org.multipaz.device.DeviceAssertionMaker
import org.multipaz.rpc.handler.RpcDispatcher
import org.multipaz.rpc.handler.RpcDispatcherHttp
import org.multipaz.rpc.handler.RpcDispatcherLocal
import org.multipaz.rpc.handler.RpcExceptionMap
import org.multipaz.rpc.handler.RpcNotificationsLocal
import org.multipaz.rpc.handler.RpcNotifier
import org.multipaz.rpc.handler.RpcNotifierPoll
import org.multipaz.rpc.handler.RpcPollHttp
import org.multipaz.rpc.handler.SimpleCipher
import org.multipaz.rpc.transport.HttpTransport
import org.multipaz.provisioning.ApplicationSupport
import org.multipaz.provisioning.ClientAuthentication
import org.multipaz.provisioning.IssuingAuthority
import org.multipaz.provisioning.WalletApplicationCapabilities
import org.multipaz.provisioning.WalletServer
import org.multipaz.provisioning.WalletServerStub
import org.multipaz.provisioning.wallet.WalletServerState
import org.multipaz.device.DeviceCheck
import org.multipaz.device.DeviceAttestation
import org.multipaz.securearea.SecureAreaProvider
import org.multipaz.storage.Storage
import org.multipaz.storage.StorageTableSpec
import org.multipaz.util.Logger
import org.multipaz.wallet.SettingsModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.encodeToByteString
import org.multipaz.provisioning.AuthenticationStub
import org.multipaz.rpc.handler.RpcAuthIssuerAssertion
import org.multipaz.rpc.handler.RpcDispatcherAuth
import kotlin.time.Duration.Companion.seconds

/**
 * An object used to connect to a remote wallet server.
 *
 * Wallet app can function in two modes: full wallet server mode and minimalistic server mode.
 *
 * In full wallet server mode, wallet app communicates to the wallet server and the wallet server
 * communicates to the actual issuing authority server(s). The advantage of this mode is that
 * the system tends to be much more robust when the app needs to communicate to its own server
 * only. In particular this allows wallet app vendor to decouple evolution of the wallet app from
 * the evolution of the multitude of the issuing authority servers. Also, wallet server
 * functionality only needs to be implemented once and not for every client/mobile platform.
 *
 * In minimalistic server mode, the bulk of the "wallet server" functionality runs on the client
 * and thus the wallet app communicates to the issuing authority servers directly. Only the
 * functionality that cannot be done on the client is delegated on the server. The advantage of
 * this mode is that it tends to be easier to set up and use for development. Also, this mode
 * has less potential privacy issues, in particular if proofing data and credentials are not
 * end-to-end encrypted.
 */
class WalletServerProvider(
    private val context: Context,
    private val storage: Storage,
    private val secureAreaProvider: SecureAreaProvider<AndroidKeystoreSecureArea>,
    private val settingsModel: SettingsModel,
    private val getWalletApplicationCapabilities: suspend () -> WalletApplicationCapabilities
) {
    private val instanceLock = Mutex()
    private var instance: WalletServer? = null
    private val issuingAuthorityMap = mutableMapOf<String, IssuingAuthority>()
    private var applicationSupportSupplier: ApplicationSupportSupplier? = null

    private var notificationsJob: Job? = null
    private var resetListeners = mutableListOf<()->Unit>()

    val assertionMaker = DeviceAssertionMaker { assertionFactory ->
        val applicationSupportConnection = applicationSupportSupplier!!.getApplicationSupport()
        DeviceCheck.generateAssertion(
            secureArea = secureAreaProvider.get(),
            deviceAttestationId = applicationSupportConnection.deviceAttestationId,
            assertion = assertionFactory(applicationSupportConnection.clientId)
        )
    }

    companion object {
        private const val TAG = "WalletServerProvider"

        private val RECONNECT_DELAY_INITIAL = 1.seconds
        private val RECONNECT_DELAY_MAX = 30.seconds

        private val noopCipher = object : SimpleCipher {
            override fun encrypt(plaintext: ByteArray): ByteArray {
                return plaintext
            }

            override fun decrypt(ciphertext: ByteArray): ByteArray {
                return ciphertext
            }
        }

        private val hostsTableSpec = StorageTableSpec(
            name = "Hosts",
            supportExpiration = false,
            supportPartitions = false
        )
    }

    private val baseUrl: String
        get() = settingsModel.walletServerUrl.value!!

    init {
        settingsModel.walletServerUrl.observeForever {
            clearInstance()
        }
        settingsModel.minServerUrl.observeForever {
            if (settingsModel.walletServerUrl.value == "dev:") {
                clearInstance()
            }
        }
    }

    private fun clearInstance() {
        if (instance == null) {
            // Nothing to do, and we don't want to notify reset listeners
            return
        }
        runBlocking {
            instanceLock.withLock {
                Logger.i(TAG, "Resetting wallet server...")
                try {
                    for (issuingAuthority in issuingAuthorityMap.values) {
                        issuingAuthority.dispose()
                    }
                    applicationSupportSupplier?.release()
                } catch (err: Exception) {
                    Logger.e(TAG, "Error shutting down Wallet Server connection", err)
                }
                issuingAuthorityMap.clear()
                applicationSupportSupplier = null
                instance = null
                notificationsJob?.cancel()
                notificationsJob = null
                Logger.i(TAG, "Done resetting wallet server")
            }
            resetListeners.forEach { it() }
        }
    }

    fun addResetListener(listener: () -> Unit) {
        resetListeners.add(listener)
    }

    /**
     * Connects to the remote wallet server.
     *
     * This process includes running through the authentication flow to prove to the remote wallet
     * server that the client is in good standing, e.g. that Verified Boot is GREEN, Android patch
     * level is sufficiently fresh, the app signature keys are as expected, and so on.
     *
     * This is usually only called for operations where the wallet actively needs to interact
     * with the wallet server, e.g. when adding a new document or refreshing state. When the
     * call succeeds, the resulting instance is cached and returned immediately in future calls.
     *
     * @return A [WalletServer] which can be used to interact with the remote wallet server.
     * @throws HttpTransport.ConnectionException if unable to connect.
     */
    suspend fun getWalletServer(): WalletServer {
        instanceLock.withLock {
            if (instance == null) {
                Logger.i(TAG, "Creating new WalletServer instance: $baseUrl")
                val connection = estableshWalletServerConnection(baseUrl)
                instance = connection.server
                applicationSupportSupplier = connection.applicationSupportSupplier
                Logger.i(TAG, "Created new WalletServer instance: $baseUrl")
            } else {
                Logger.i(TAG, "Reusing existing WalletServer instance: $baseUrl")
            }
            return instance!!
        }
    }

    /**
     * Connects to the remote wallet server, waiting for the server connection if needed.
     */
    private suspend fun waitForWalletServer(): WalletServer {
        var delay = RECONNECT_DELAY_INITIAL
        while (true) {
            try {
                return getWalletServer()
            } catch (err: HttpTransport.ConnectionException) {
                delay(delay)
                delay *= 2
                if (delay > RECONNECT_DELAY_MAX) {
                    delay = RECONNECT_DELAY_MAX
                }
            }
        }
    }

    /**
     * Gets issuing authority by its id, caching instances. If unable to connect, suspend
     * and wait until connecting is possible.
     */
    suspend fun getIssuingAuthority(issuingAuthorityId: String): IssuingAuthority {
        val instance = waitForWalletServer()
        var delay = RECONNECT_DELAY_INITIAL
        while (true) {
            try {
                instanceLock.withLock {
                    var issuingAuthority = issuingAuthorityMap[issuingAuthorityId]
                    if (issuingAuthority == null) {
                        issuingAuthority = instance.getIssuingAuthority(issuingAuthorityId)
                        issuingAuthorityMap[issuingAuthorityId] = issuingAuthority
                    }
                    return issuingAuthority
                }
            } catch (err: HttpTransport.ConnectionException) {
                delay(delay)
                delay *= 2
                if (delay > RECONNECT_DELAY_MAX) {
                    delay = RECONNECT_DELAY_MAX
                }
            }
        }
    }

    /**
     * Creates an Issuing Authority by the [credentialIssuerUri] and [credentialConfigurationId],
     * caching instances. If unable to connect, suspend and wait until connecting is possible.
     */
    suspend fun createOpenid4VciIssuingAuthorityByUri(
        credentialIssuerUri:String,
        credentialConfigurationId: String
    ): IssuingAuthority {
        // Not allowed per spec, but double-check, so there are no surprises.
        check(credentialIssuerUri.indexOf('#') < 0)
        check(credentialConfigurationId.indexOf('#') < 0)
        val id = "openid4vci#$credentialIssuerUri#$credentialConfigurationId"
        return getIssuingAuthority(id)
    }

    /**
     * Gets ApplicationSupport object and data necessary to make use of it.
     * It always comes from the server (either full wallet server or minimal wallet server).
     */
    suspend fun getApplicationSupportConnection(): ApplicationSupportConnection {
        getWalletServer()
        return applicationSupportSupplier!!.getApplicationSupport()
    }

    private suspend fun estableshWalletServerConnection(baseUrl: String): WalletServerConnection {
        val dispatcher: RpcDispatcher
        val notifier: RpcNotifier
        val exceptionMapBuilder = RpcExceptionMap.Builder()
        var applicationSupportSupplier: ApplicationSupportSupplier? = null
        WalletServerState.registerExceptions(exceptionMapBuilder)
        if (baseUrl == "dev:") {
            val builder = RpcDispatcherLocal.Builder()
            WalletServerState.registerAll(builder)
            notifier = RpcNotificationsLocal(noopCipher)
            applicationSupportSupplier = ApplicationSupportSupplier() {
                val minServer = estableshWalletServerConnection(settingsModel.minServerUrl.value!!)
                minServer.applicationSupportSupplier.getApplicationSupport()
            }
            val environment = LocalDevelopmentEnvironment(
                context, settingsModel, assertionMaker,
                secureAreaProvider, notifier, applicationSupportSupplier)
            dispatcher = WrapperRpcDispatcher(builder.build(
                environment,
                noopCipher,
                exceptionMapBuilder.build()
            ))
        } else {
            val httpClient = WalletHttpTransport(baseUrl)
            val poll = RpcPollHttp(httpClient)
            notifier = RpcNotifierPoll(poll)
            notificationsJob = CoroutineScope(Dispatchers.IO).launch {
                notifier.loop()
            }
            dispatcher = RpcDispatcherHttp(httpClient, exceptionMapBuilder.build())
        }

        val hostsTable = storage.getTable(hostsTableSpec)
        val connectionDataBytes = hostsTable.get(key = baseUrl)
        var connectionData = if (connectionDataBytes == null) {
            null
        } else {
            WalletServerConnectionData.fromCbor(connectionDataBytes.toByteArray())
        }

        // RPC entry point that does not require authorization, it is used to set up
        // authorization parameters with the server (so these parameters can be used for subsequent
        // RPC communication).
        val authentication = AuthenticationStub(
            endpoint = "auth",
            dispatcher = dispatcher,
            notifier = notifier
        )

        val challenge = authentication.requestChallenge(connectionData?.clientId ?: "")
        val deviceAttestation: DeviceAttestation?
        if (connectionData?.clientId != challenge.clientId) {
            // new client
            val result = DeviceCheck.generateAttestation(
                secureAreaProvider.get(),
                challenge.clientId.encodeToByteString()
            )
            deviceAttestation = result.deviceAttestation
            connectionData = WalletServerConnectionData(
                clientId = challenge.clientId,
                deviceAttestationId = result.deviceAttestationId
            )
            if (connectionDataBytes == null) {
                hostsTable.insert(
                    data = ByteString(connectionData.toCbor()),
                    key = baseUrl
                )
            } else {
                hostsTable.update(
                    data = ByteString(connectionData.toCbor()),
                    key = baseUrl
                )
            }
        } else {
            deviceAttestation = null
        }
        authentication.authenticate(ClientAuthentication(
            deviceAttestation,
            DeviceCheck.generateAssertion(
                secureAreaProvider.get(),
                connectionData.deviceAttestationId,
                AssertionNonce(challenge.nonce)
            ),
            getWalletApplicationCapabilities()
        ))

        val authorizedDispatcher = RpcDispatcherAuth(
            base = dispatcher,
            rpcAuthIssuer = RpcAuthIssuerAssertion(
                clientId = connectionData.clientId,
                secureArea = secureAreaProvider.get(),
                deviceAttestationId = connectionData.deviceAttestationId
            )
        )

        // "root" is the entry point for the server, see RpcState annotation on
        // org.multipaz.provisioning.wallet.WalletServerState
        val walletServer = WalletServerStub(
            endpoint = "root",
            dispatcher = authorizedDispatcher,
            notifier = notifier
        )

        if (applicationSupportSupplier == null) {
            applicationSupportSupplier = ApplicationSupportSupplier {
                ApplicationSupportConnection(
                    applicationSupport = walletServer.applicationSupport(),
                    clientId = connectionData.clientId,
                    deviceAttestationId = connectionData.deviceAttestationId
                )
            }
        }

        return WalletServerConnection(walletServer, applicationSupportSupplier)
    }

    /**
     * Flow handler that delegates to a base, ensuring that the work is done in IO dispatchers
     * and logging the calls.
     */
    internal class WrapperRpcDispatcher(private val base: RpcDispatcher): RpcDispatcher {
        companion object {
            const val TAG = "MultipazRpc"
        }

        override val exceptionMap: RpcExceptionMap
            get() = base.exceptionMap

        override suspend fun dispatch(flow: String, method: String, args: DataItem): List<DataItem> {
            Logger.i(TAG, "POST [${Thread.currentThread().name}] $flow/$method")
            val start = System.nanoTime()
            try {
                return withContext(Dispatchers.IO) {
                    base.dispatch(flow, method, args)
                }
            } finally {
                val duration = System.nanoTime() - start
                Logger.i(TAG, "${durationText(duration)} [${Thread.currentThread().name}] $flow/$method")
            }
        }

        private fun durationText(durationNano: Long): String {
            return (durationNano/1000000).toString().padEnd(4, ' ')
        }
    }

    internal class ApplicationSupportSupplier(
        val factory: suspend () -> ApplicationSupportConnection
    ) {
        private var connection: ApplicationSupportConnection? = null

        suspend fun getApplicationSupport(): ApplicationSupportConnection {
            if (connection == null) {
                connection = factory()
            }
            return connection!!
        }

        suspend fun release() {
            connection?.applicationSupport?.dispose()
        }
    }

    class ApplicationSupportConnection(
        val applicationSupport: ApplicationSupport,
        val clientId: String,
        val deviceAttestationId: String
    )

    internal class WalletServerConnection(
        val server: WalletServer,
        val applicationSupportSupplier: ApplicationSupportSupplier
    )
}