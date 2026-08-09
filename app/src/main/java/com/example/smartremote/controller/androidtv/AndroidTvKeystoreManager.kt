package com.example.smartremote.controller.androidtv

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.example.smartremote.util.Constants
import com.example.smartremote.util.CredentialStore
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date
import javax.security.auth.x500.X500Principal

/**
 * *** NOVO - v0.9, item 3 (Android TV) ***
 *
 * Responsável pela identidade criptográfica que o Android TV Remote v2
 * exige de cada cliente - ver KDoc de [AndroidTvRemoteProtocol] para o
 * algoritmo de pareamento que consome essa identidade.
 *
 * ## Decisão de segurança (para validação - ver pedido da v0.9)
 * Diferente do token simples de texto que Samsung/LG guardam via
 * [CredentialStore] (`String` puro em SharedPreferences), aqui existe uma
 * CHAVE PRIVADA de verdade, que autentica o app perante a TV via TLS
 * mútuo indefinidamente (até o usuário esquecer a TV). Guardar isso como
 * texto puro seria um retrocesso claro de segurança. Por isso:
 *
 * - A chave privada é gerada e vive inteiramente dentro do
 *   `AndroidKeyStore` (`KeyStore.getInstance("AndroidKeyStore")`) - nunca
 *   é exportada para memória do processo como bytes brutos, nem
 *   persistida em SharedPreferences/arquivo. O sistema operacional que
 *   guarda e protege o material da chave; o app só pede para o Keystore
 *   USAR a chave (assinar/autenticar TLS), nunca pede para "ler" a chave.
 * - [CredentialStore] continua sendo reaproveitado (não criei um
 *   mecanismo de armazenamento novo) - mas o que ele guarda para o
 *   Android TV é só o ALIAS (nome) da entrada no Keystore
 *   ([Constants.ANDROID_TV_CREDENTIAL_TYPE]), não a chave em si. Avaliei
 *   estender CredentialStore para "saber" sobre Keystore diretamente, mas
 *   decidi que a responsabilidade fica mais clara separada: CredentialStore
 *   continua 100% genérico (só strings, sem saber o que cada string
 *   significa), e esta classe (específica de fabricante, como o resto do
 *   protocolo Android TV) sabe que aquele alias aponta pro Keystore.
 * - O certificado é AUTOASSINADO (subject = CN=SmartRemote) - aceitável
 *   porque a conexão é só na rede local (mesma justificativa dos tokens
 *   de Samsung/LG, que também confiam no certificado da TV sem CA), e é
 *   exatamente o que o app oficial "Google TV" também faz (confirmado
 *   durante a pesquisa do protocolo).
 * - RSA-2048, não EC: o algoritmo de verificação do código de pareamento
 *   exibido na TV (ver [AndroidTvRemoteProtocol.computePairingSecret])
 *   opera especificamente sobre MÓDULO e EXPOENTE PÚBLICO da chave RSA -
 *   é assim que o protocolo v2 foi desenhado (confirmado contra
 *   implementações de referência mantidas ativamente), então a escolha do
 *   algoritmo não é livre aqui.
 */
class AndroidTvKeystoreManager(
    private val context: Context,
    private val credentialDeviceId: String
) {

    private val keyStore: KeyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

    /** Alias desta TV específica no AndroidKeyStore - isolado por TV pareada, nunca reaproveitado entre TVs diferentes. */
    private val alias: String = Constants.ANDROID_TV_KEYSTORE_ALIAS_PREFIX + credentialDeviceId.hashCode().toUInt().toString(16)

    /**
     * Garante que existe um par de chaves + certificado autoassinado para
     * esta TV, gerando um novo se necessário (primeira vez pareando com
     * ela). Idempotente: chamadas seguintes reaproveitam a mesma chave -
     * importante porque o pareamento já feito com a TV depende da MESMA
     * identidade (a TV guarda o certificado do app do lado dela).
     */
    fun ensureKeyPair(): AndroidTvIdentity {
        if (!keyStore.containsAlias(alias)) {
            generateKeyPair()
            CredentialStore.save(context, credentialDeviceId, Constants.ANDROID_TV_CREDENTIAL_TYPE, alias)
        }
        return loadIdentity()
    }

    /** Identidade já existente, ou `null` se esta TV nunca foi pareada (nenhuma chave gerada ainda). */
    fun existingIdentity(): AndroidTvIdentity? {
        if (!keyStore.containsAlias(alias)) return null
        return loadIdentity()
    }

    /**
     * Remove a chave/certificado desta TV do Keystore - chamado por
     * [com.example.smartremote.manager.TvManager.forgetDevice] (via
     * credentialTypeFor) ao esquecer uma TV Android TV pareada. Diferente
     * de CredentialStore.clear() (que só apaga a string do alias),
     * também precisa apagar a entrada real no Keystore, senão ela fica
     * órfã lá para sempre.
     */
    fun deleteKeyPair() {
        if (keyStore.containsAlias(alias)) {
            keyStore.deleteEntry(alias)
        }
        CredentialStore.clear(context, credentialDeviceId, Constants.ANDROID_TV_CREDENTIAL_TYPE)
    }

    private fun loadIdentity(): AndroidTvIdentity {
        val privateKey = keyStore.getKey(alias, null) as PrivateKey
        val certificate = keyStore.getCertificate(alias) as X509Certificate
        return AndroidTvIdentity(privateKey, certificate)
    }

    private fun generateKeyPair() {
        val subject = X500Principal("CN=${Constants.ANDROID_TV_CLIENT_NAME}")
        val now = Date()
        val notAfter = Date(now.time + VALIDITY_YEARS_MS)

        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setKeySize(RSA_KEY_SIZE_BITS)
            // O handshake TLS pode negociar qualquer uma dessas
            // combinações de digest/padding dependendo da versão do TLS
            // e do que a TV suporta (TLS 1.2 tipicamente PKCS1 com
            // SHA-256/384/512; TLS 1.3 pode preferir RSA-PSS) - restringir
            // a uma única combinação arriscaria uma falha de assinatura
            // no meio do handshake dependendo de qual a TV escolhesse.
            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
            .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, KeyProperties.SIGNATURE_PADDING_RSA_PSS)
            // setCertificateXxx (API 24+) faz o próprio AndroidKeyStore
            // emitir um certificado autoassinado envolvendo a chave
            // gerada - é isso que permite usar esta chave em um
            // SSLContext (KeyManager) sem precisar de nenhuma lib externa
            // de manipulação de certificado X.509.
            .setCertificateSubject(subject)
            .setCertificateSerialNumber(BigInteger.valueOf(now.time))
            .setCertificateNotBefore(now)
            .setCertificateNotAfter(notAfter)
            .build()

        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, KEYSTORE_PROVIDER)
        generator.initialize(spec)
        generator.generateKeyPair()
    }

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val RSA_KEY_SIZE_BITS = 2048
        private const val VALIDITY_YEARS_MS = 20L * 365 * 24 * 60 * 60 * 1000 // ~20 anos - só precisa não expirar durante o uso normal do app
    }
}

/** Par chave privada + certificado (público) usado para o TLS mútuo com a TV. */
data class AndroidTvIdentity(
    val privateKey: PrivateKey,
    val certificate: X509Certificate
)
