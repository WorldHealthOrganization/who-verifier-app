package org.who.ddccverifier.services.trust

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.net.URL
import java.security.PublicKey
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.*
import com.fasterxml.jackson.annotation.JsonValue
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.nimbusds.jose.crypto.bc.BouncyCastleProviderSingleton
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

/**
 * Resolve Keys for Verifiers
 */
object TrustRegistry {
    // Using old java.time to keep compatibility down to Android SDK 22.
    private var df: DateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")

    enum class Status(@JsonValue val status: String) {
        CURRENT("current"), EXPIRED("expired"), TERMINATED("terminated"), REVOKED("revoked")
    }
    enum class Type(@JsonValue val type: String) {
        ISSUER("issuer"), VERIFIER("verifier"), TRUST_REGISTRY("trust_registry")
    }
    enum class Framework(@JsonValue val framework: String) {
        CRED("CRED"),
        DCC("EUDCC"),
        ICAO("ICAO"),
        SHC("SmartHealthCards"),
        DIVOC("DIVOC")
    }

    object DidDocumentDeserializer : JsonDeserializer<PublicKey>() {
        override fun deserialize(p: JsonParser, ctxt: DeserializationContext): PublicKey {
            val node = p.readValueAsTree<JsonNode>()

            return if (node.isTextual) {
                // PEM File
                KeyUtils.publicKeyFromPEM(node.asText())
            } else {
                if (node.has("x"))
                    KeyUtils.ecPublicKeyFromCoordinate(node.get("x").asText(), node.get("y").asText())
                else
                    KeyUtils.rsaPublicKeyFromModulusExponent(node.get("n").asText(), node.get("e").asText())
            }
        }
    }

    data class TrustedEntity(
        val displayName: Map<String, String>,
        val displayLogo: String?,
        val entityType: Type,
        val status: Status,
        val statusDetail: String?,
        val validFromDT: Date?,
        val validUntilDT: Date?,
        @JsonDeserialize(using = DidDocumentDeserializer::class)
        val didDocument: PublicKey,
        val credentialType: List<String>,
    )

    private val registry: MutableMap<Framework, MutableMap<String, TrustedEntity>> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.addProvider(BouncyCastleProviderSingleton.getInstance())

        val result = URL("https://raw.githubusercontent.com/Path-Check/trust-registry/main/registry.json").readText()
        //TODO: Downloading this JSON takes 500ms

        val mapper = jacksonObjectMapper()
        val typeRef: TypeReference<MutableMap<Framework, MutableMap<String, TrustedEntity>>> =
            object : TypeReference<MutableMap<Framework, MutableMap<String, TrustedEntity>>>() {}

        //TODO: Parsing this JSON takes 5s
        val reg = mapper.readValue(result, typeRef)
        addTestKeys(reg)
        return@lazy reg
    }

    private fun addTestKeys(registry: MutableMap<Framework, MutableMap<String, TrustedEntity>>) {
        registry[Framework.DCC]?.put(
            "MTE=", TrustedEntity(
                mapOf("en" to "Test Keys for the WHO\'s DDCC"),
                null,
                Type.ISSUER,
                Status.CURRENT,
                "WHO Test Keys",
                df.parse("2021-01-01T08:00:00.000Z"),
                df.parse("2021-12-01T08:00:00.000Z"),
                KeyUtils.ecPublicKeyFromCoordinate(
                    "143329cce7868e416927599cf65a34f3ce2ffda55a7eca69ed8919a394d42f0f".chunked(2)
                        .map { it.toInt(16).toByte() }
                        .toByteArray(),
                    "60f7f1a780d8a783bfb7a2dd6b2796e8128dbbcef9d3d168db9529971a36e7b9".chunked(2)
                        .map { it.toInt(16).toByte() }
                        .toByteArray()
                ),
                listOf("VS")
            )
        )

        registry[Framework.SHC]?.put(
            "https://spec.smarthealth.cards/examples/issuer#3Kfdg-XwP-7gXyywtUfUADwBumDOPKMQx-iELL11W9s", TrustedEntity(
                mapOf("en" to "Test Keys for the SmartHealth Cards"),
                null,
                Type.ISSUER,
                Status.CURRENT,
                "SHC Test Keys",
                df.parse("2021-01-01T08:00:00.000Z"),
                df.parse("2021-12-01T08:00:00.000Z"),
                KeyUtils.ecPublicKeyFromCoordinate(
                    "11XvRWy1I2S0EyJlyf_bWfw_TQ5CJJNLw78bHXNxcgw",
                    "eZXwxvO1hvCY0KucrPfKo7yAyMT6Ajc3N7OkAB6VYy8"),
                listOf("VS")
            )
        )
    }

    fun init() {
        registry[Framework.DCC]?.get("MTE=")
    }

    fun resolve(framework: Framework, kid: String): TrustedEntity? {
        return registry[framework]?.get(kid)
    }
}