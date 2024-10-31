package com.android.identity_credential.wallet

import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.identity.credential.Credential
import com.android.identity.document.Document
import com.android.identity.document.DocumentStore
import com.android.identity.issuance.CredentialFormat
import com.android.identity.issuance.DocumentExtensions.documentConfiguration
import com.android.identity.issuance.DocumentExtensions.documentIdentifier
import com.android.identity.issuance.DocumentExtensions.issuingAuthorityConfiguration
import com.android.identity.issuance.DocumentExtensions.issuingAuthorityIdentifier
import com.android.identity.issuance.DocumentExtensions.refreshState
import com.android.identity.issuance.IssuingAuthority
import com.android.identity.issuance.ProofingFlow
import com.android.identity.issuance.RegistrationResponse
import com.android.identity.issuance.evidence.EvidenceRequest
import com.android.identity.issuance.evidence.EvidenceRequestIcaoNfcTunnel
import com.android.identity.issuance.evidence.EvidenceRequestOpenid4Vp
import com.android.identity.issuance.evidence.EvidenceResponse
import com.android.identity.issuance.evidence.EvidenceResponseIcaoNfcTunnel
import com.android.identity.issuance.remote.WalletServerProvider
import com.android.identity.util.Logger
import com.android.identity.util.fromBase64Url
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.io.bytestring.buildByteString
import org.json.JSONObject

class ProvisioningViewModel : ViewModel() {

    companion object {
        private const val TAG = "ProvisioningViewModel"
    }

    enum class State {
        IDLE,
        CREDENTIAL_REGISTRATION,
        EVIDENCE_REQUESTS_READY,
        SUBMITTING_EVIDENCE,
        PROOFING_COMPLETE,
        FAILED,
    }

    var state = mutableStateOf(ProvisioningViewModel.State.IDLE)

    var error: Throwable? = null

    private lateinit var issuer: IssuingAuthority

    /*
     Backing field + StateFlow that is observed on MainActivity's composable whose value is updated
     from onNewIntent() when a OID4VCI Credential Offer deep link is intercepted in MainActivity.
     */
    private val _newCredentialOfferIntentReceived = MutableStateFlow<Pair<String, String>?>(null)
    val newCredentialOfferIntentReceived: StateFlow<Pair<String, String>?> =
        _newCredentialOfferIntentReceived.asStateFlow()

    /**
     * Trigger a recomposition of MainActivity from onNewIntent() after an OID4VCI deep link is
     * intercepted. If both [credentialIssuerUri] and [credentialConfigurationId] are [null] then
     * set observable value to [null] rather than Pair(null,null).
     */
    fun onNewCredentialOfferIntent(
        credentialIssuerUri: String?,
        credentialConfigurationId: String?
    ) {
        if (credentialIssuerUri != null && credentialConfigurationId != null) {
            _newCredentialOfferIntentReceived.value =
                Pair(credentialIssuerUri, credentialConfigurationId)
        } else {
            _newCredentialOfferIntentReceived.value = null
        }
    }

    fun reset() {
        state.value = State.IDLE
        error = null
        document = null
        proofingFlow = null
        evidenceRequests = null
        currentEvidenceRequestIndex = 0
        nextEvidenceRequest.value = null
        selectedOpenid4VpCredential.value = null
        documentStore = null
        settingsModel = null
    }

    private var proofingFlow: ProofingFlow? = null

    var document: Document? = null
    private var evidenceRequests: List<EvidenceRequest>? = null
    private var currentEvidenceRequestIndex: Int = 0
    private var documentStore: DocumentStore? = null
    private var settingsModel: SettingsModel? = null

    val nextEvidenceRequest = mutableStateOf<EvidenceRequest?>(null)
    val selectedOpenid4VpCredential = mutableStateOf<Credential?>(null)

    fun start(
        walletServerProvider: WalletServerProvider,
        documentStore: DocumentStore,
        settingsModel: SettingsModel,
        // PID-based mdoc or sd-jwt
        issuerIdentifier: String?,
        // OID4VCI credential offer
        credentialIssuerUri: String? = null,
        credentialIssuerConfigurationId: String? = null,
    ) {
        this.documentStore = documentStore
        this.settingsModel = settingsModel
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (credentialIssuerUri != null) {
                    issuer = walletServerProvider.createOpenid4VciIssuingAuthorityByUri(
                        credentialIssuerUri,
                        credentialIssuerConfigurationId!!
                    )
                } else {
                    issuer = walletServerProvider.getIssuingAuthority(issuerIdentifier!!)
                }
                val issuerConfiguration = issuer.getConfiguration()

                state.value = State.CREDENTIAL_REGISTRATION
                val createDocumentKeyFlow = this@ProvisioningViewModel.issuer.register()
                val documentRegistrationConfiguration =
                    createDocumentKeyFlow.getDocumentRegistrationConfiguration()
                val issuerDocumentIdentifier = documentRegistrationConfiguration.documentId
                val response = RegistrationResponse(
                    settingsModel.developerModeEnabled.value!!
                )
                createDocumentKeyFlow.sendDocumentRegistrationResponse(response)
                createDocumentKeyFlow.complete()

                val documentIdentifier =
                    issuerConfiguration.identifier + "_" + issuerDocumentIdentifier
                document = documentStore.createDocument(documentIdentifier)
                val pendingDocumentConfiguration = issuerConfiguration.pendingDocumentInformation

                document!!.let {
                    it.issuingAuthorityIdentifier = issuerConfiguration.identifier
                    it.documentIdentifier = issuerDocumentIdentifier
                    it.documentConfiguration = pendingDocumentConfiguration
                    it.issuingAuthorityConfiguration = issuerConfiguration
                    it.refreshState(walletServerProvider)
                }

                proofingFlow = issuer.proof(issuerDocumentIdentifier)
                evidenceRequests = proofingFlow!!.getEvidenceRequests()
                currentEvidenceRequestIndex = 0
                Logger.d(TAG, "ers0 ${evidenceRequests!!.size}")
                if (evidenceRequests!!.size == 0) {
                    state.value = State.PROOFING_COMPLETE
                    document!!.let {
                        it.refreshState(walletServerProvider)
                    }
                    documentStore.addDocument(document!!)
                    proofingFlow!!.complete()
                } else {
                    selectViableEvidenceRequest()
                    state.value = State.EVIDENCE_REQUESTS_READY
                }
            } catch (e: Throwable) {
                if (document != null) {
                    documentStore.deleteDocument(document!!.name)
                }
                Logger.w(TAG, "Error registering Document", e)
                e.printStackTrace()
                error = e
                state.value = State.FAILED
            }
        }
    }

    fun evidenceCollectionFailed(
        error: Throwable,
        walletServerProvider: WalletServerProvider,
        documentStore: DocumentStore
    ) {
        if (document != null) {
            documentStore.deleteDocument(document!!.name)
        }
        Logger.w(TAG, "Error collecting evidence", error)
        this.error = error
        state.value = State.FAILED
    }

    fun provideEvidence(
        evidence: EvidenceResponse,
        walletServerProvider: WalletServerProvider,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                state.value = State.SUBMITTING_EVIDENCE

                proofingFlow!!.sendEvidence(evidence)

                evidenceRequests = proofingFlow!!.getEvidenceRequests()
                currentEvidenceRequestIndex = 0
                Logger.d(TAG, "ers1 ${evidenceRequests!!.size}")
                if (evidenceRequests!!.size == 0) {
                    state.value = State.PROOFING_COMPLETE
                    document!!.refreshState(walletServerProvider)
                    documentStore!!.addDocument(document!!)
                    proofingFlow!!.complete()
                    document!!.refreshState(walletServerProvider)
                } else {
                    selectViableEvidenceRequest()
                    state.value = State.EVIDENCE_REQUESTS_READY
                }
            } catch (e: Throwable) {
                if (document != null) {
                    documentStore!!.deleteDocument(document!!.name)
                }
                Logger.w(TAG, "Error submitting evidence", e)
                e.printStackTrace()
                error = e
                state.value = State.FAILED
            }
        }
    }

    /**
     * Handles request/response exchange through NFC tunnel.
     *
     * This must be called on a backround thread and will block until the tunnel is
     * closed.
     *
     * @param handler a handler that communicates to the chip passing in the requests that it
     *     gets as parameter returning responses wrapped in [EvidenceResponseIcaoTunnel].
     */
    fun runIcaoNfcTunnel(handler: (EvidenceRequestIcaoNfcTunnel) -> EvidenceResponseIcaoNfcTunnel) {
        // must run on a background thread
        if (Looper.getMainLooper().isCurrentThread) {
            throw IllegalStateException("Must not be called on main thread")
        }

        runBlocking {
            // handshake
            proofingFlow!!.sendEvidence(EvidenceResponseIcaoNfcTunnel(buildByteString {}))

            while (true) {
                val requests = proofingFlow!!.getEvidenceRequests()
                if (requests.size != 1) {
                    break
                }
                val request = requests[0]
                if (request !is EvidenceRequestIcaoNfcTunnel) {
                    break
                }

                val response = handler(request)
                proofingFlow!!.sendEvidence(response)
            }
        }
    }

    fun finishTunnel() {
        viewModelScope.launch(Dispatchers.IO) {
            // This is a hack needed since evidenceRequests is not a state (and it should be).
            // TODO: remove this once evidenceRequests becomes state
            state.value = State.SUBMITTING_EVIDENCE
            state.value = State.EVIDENCE_REQUESTS_READY
            evidenceRequests = proofingFlow!!.getEvidenceRequests()
            currentEvidenceRequestIndex = 0
            selectViableEvidenceRequest()
        }
    }

    fun moveToNextEvidenceRequest(): Boolean {
        currentEvidenceRequestIndex++
        return selectViableEvidenceRequest()
    }

    private fun selectViableEvidenceRequest(): Boolean {
        val evidenceRequests = this.evidenceRequests!!
        if (currentEvidenceRequestIndex >= evidenceRequests.size) {
            return false
        }
        val request = evidenceRequests[currentEvidenceRequestIndex]
        if (request is EvidenceRequestOpenid4Vp) {
            val openid4VpCredential = selectCredential(request.request)
            if (openid4VpCredential != null) {
                // EvidenceRequestOpenid4Vp must not come by itself
                nextEvidenceRequest.value = request
                selectedOpenid4VpCredential.value = openid4VpCredential
            } else {
                currentEvidenceRequestIndex++
                if (currentEvidenceRequestIndex >= evidenceRequests.size) {
                    return false
                }
                nextEvidenceRequest.value = evidenceRequests[currentEvidenceRequestIndex]
                selectedOpenid4VpCredential.value = null
            }
        } else {
            nextEvidenceRequest.value = request
            selectedOpenid4VpCredential.value = null
        }
        return true
    }

    private fun selectCredential(request: String): Credential? {
        val parts = request.split('.')
        val openid4vpRequest = JSONObject(String(parts[1].fromBase64Url()))

        val presentationDefinition = openid4vpRequest.getJSONObject("presentation_definition")
        val inputDescriptors = presentationDefinition.getJSONArray("input_descriptors")
        if (inputDescriptors.length() != 1) {
            throw IllegalArgumentException("Only support a single input input_descriptor")
        }
        val inputDescriptor = inputDescriptors.getJSONObject(0)!!
        val docType = inputDescriptor.getString("id")

        // For now, we only respond to the first credential being requested.
        //
        // NOTE: openid4vp spec gives a non-normative example of multiple input descriptors
        // as "alternatives credentials", see
        //
        //  https://openid.net/specs/openid-4-verifiable-presentations-1_0.html#section-5.1-6
        //
        // Also note identity.foundation says all input descriptors MUST be satisfied, see
        //
        //  https://identity.foundation/presentation-exchange/spec/v2.0.0/#input-descriptor
        //
        val credentialFormat = CredentialFormat.MDOC_MSO
        val document = firstMatchingDocument(credentialFormat, docType)
        return document?.findCredential(WalletApplication.CREDENTIAL_DOMAIN_MDOC, Clock.System.now())
    }

    private fun firstMatchingDocument(
        credentialFormat: CredentialFormat,
        docType: String
    ): Document? {
        // prefer the credential which is on-screen if possible
        val credentialIdFromPager: String? = settingsModel!!.focusedCardId.value
        if (credentialIdFromPager != null
            && canDocumentSatisfyRequest(credentialIdFromPager, credentialFormat, docType)
        ) {
            return documentStore!!.lookupDocument(credentialIdFromPager)
        }

        val docId = documentStore!!.listDocuments().firstOrNull { credentialId ->
            canDocumentSatisfyRequest(credentialId, credentialFormat, docType)
        }
        return docId?.let { documentStore!!.lookupDocument(it) }
    }

    private fun canDocumentSatisfyRequest(
        credentialId: String,
        credentialFormat: CredentialFormat,
        docType: String
    ): Boolean {
        val document = documentStore!!.lookupDocument(credentialId) ?: return false
        val documentConfiguration = document.documentConfiguration
        return when (credentialFormat) {
            CredentialFormat.MDOC_MSO -> documentConfiguration.mdocConfiguration?.docType == docType
            CredentialFormat.SD_JWT_VC -> documentConfiguration.sdJwtVcDocumentConfiguration != null
        }
    }
}