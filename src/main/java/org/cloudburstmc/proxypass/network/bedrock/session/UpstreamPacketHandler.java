package org.cloudburstmc.proxypass.network.bedrock.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import com.google.gson.GsonBuilder;
import com.google.gson.ToNumberPolicy;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.gson.io.GsonDeserializer;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import net.raphimc.mcauth.MinecraftAuth;
import net.raphimc.mcauth.step.bedrock.StepMCChain;
import net.raphimc.mcauth.step.bedrock.StepPlayFabToken;
import net.raphimc.mcauth.step.msa.StepMsaDeviceCode;
import net.raphimc.mcauth.util.MicrosoftConstants;
import org.apache.http.impl.client.CloseableHttpClient;
import org.cloudburstmc.protocol.bedrock.data.PacketCompressionAlgorithm;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacketHandler;
import org.cloudburstmc.protocol.bedrock.packet.LoginPacket;
import org.cloudburstmc.protocol.bedrock.packet.NetworkSettingsPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayStatusPacket;
import org.cloudburstmc.protocol.bedrock.packet.RequestNetworkSettingsPacket;
import org.cloudburstmc.protocol.bedrock.util.ChainValidationResult;
import org.cloudburstmc.protocol.bedrock.util.EncryptionUtils;
import org.cloudburstmc.protocol.bedrock.util.JsonUtils;
import org.cloudburstmc.protocol.common.PacketSignal;
import org.cloudburstmc.proxypass.ProxyPass;
import org.cloudburstmc.proxypass.network.bedrock.util.ForgeryUtils;
import org.jose4j.json.JsonUtil;
import org.jose4j.json.internal.json_simple.JSONObject;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.lang.JoseException;

import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;

@Log4j2
@RequiredArgsConstructor
public class UpstreamPacketHandler implements BedrockPacketHandler {
    private static final ECPublicKey MOJANG_PUBLIC_KEY;
    private static List<String> loginChain;

    static {
        try {
            MOJANG_PUBLIC_KEY = (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(
                "MHYwEAYHKoZIzj0CAQYFK4EEACIDYgAE8ELkixyLcwlZryUQcu1TvPOmI2B7vX83ndnWRUaXm74wFfa5f/lwQNTfrLVHa2PmenpGI6JhIMUJaWZrjmMj90NoKNFSNBuKdm8rYiXsfaz3K36x/1U26HpG0ZxK/V1V"
            )));
        } catch (Throwable e) {
            throw new RuntimeException("Could not initialize the required cryptography for online login", e);
        }
    }

    private final ProxyServerSession session;
    private final ProxyPass proxy;
    private JSONObject skinData;
    private JSONObject extraData;
    private List<String> chainData;
    private AuthData authData;
    private ProxyPlayerSession player;

    private static boolean verifyJwt(String jwt, PublicKey key) throws JoseException {
        JsonWebSignature jws = new JsonWebSignature();
        jws.setKey(key);
        jws.setCompactSerialization(jwt);

        return jws.verifySignature();
    }

    @Override
    public PacketSignal handle(RequestNetworkSettingsPacket packet) {
        int protocolVersion = packet.getProtocolVersion();

        if (protocolVersion != ProxyPass.PROTOCOL_VERSION) {
            PlayStatusPacket status = new PlayStatusPacket();
            if (protocolVersion > ProxyPass.PROTOCOL_VERSION) {
                status.setStatus(PlayStatusPacket.Status.LOGIN_FAILED_SERVER_OLD);
            } else {
                status.setStatus(PlayStatusPacket.Status.LOGIN_FAILED_CLIENT_OLD);
            }

            session.sendPacketImmediately(status);
            return PacketSignal.HANDLED;
        }
        session.setCodec(ProxyPass.CODEC);

        NetworkSettingsPacket networkSettingsPacket = new NetworkSettingsPacket();
        networkSettingsPacket.setCompressionThreshold(0);
        networkSettingsPacket.setCompressionAlgorithm(PacketCompressionAlgorithm.ZLIB);

        session.sendPacketImmediately(networkSettingsPacket);
        session.setCompression(PacketCompressionAlgorithm.ZLIB);
        return PacketSignal.HANDLED;
    }

    @Override
    public PacketSignal handle(LoginPacket packet) {
        try {
            ChainValidationResult chain = EncryptionUtils.validateChain(packet.getChain());

            JsonNode payload = ProxyPass.JSON_MAPPER.valueToTree(chain.rawIdentityClaims());

            if (payload.get("extraData").getNodeType() != JsonNodeType.OBJECT) {
                throw new RuntimeException("AuthData was not found!");
            }

            extraData = new JSONObject(JsonUtils.childAsType(chain.rawIdentityClaims(), "extraData", Map.class));

            this.authData = new AuthData(chain.identityClaims().extraData.displayName,
                    chain.identityClaims().extraData.identity, chain.identityClaims().extraData.xuid);

            if (payload.get("identityPublicKey").getNodeType() != JsonNodeType.STRING) {
                throw new RuntimeException("Identity Public Key was not found!");
            }
            ECPublicKey identityPublicKey = EncryptionUtils.parseKey(payload.get("identityPublicKey").textValue());

            String clientJwt = packet.getExtra();
            verifyJwt(clientJwt, identityPublicKey);
            JsonWebSignature jws = new JsonWebSignature();
            jws.setCompactSerialization(clientJwt);

            skinData = new JSONObject(JsonUtil.parseJson(jws.getUnverifiedPayload()));
            // chainData = packet.getChain();
            initializeProxySession();
        } catch (Exception e) {
            session.disconnect("disconnectionScreen.internalError.cantConnect");
            throw new RuntimeException("Unable to complete login", e);
        }
        return PacketSignal.HANDLED;
    }

    private void initializeProxySession() {
        log.debug("Initializing proxy session");
        this.proxy.newClient(this.proxy.getTargetAddress(), downstream -> {
            downstream.setCodec(ProxyPass.CODEC);
            downstream.setSendSession(this.session);
            this.session.setSendSession(downstream);

            ProxyPlayerSession proxySession = new ProxyPlayerSession(this.session, downstream, this.proxy, this.authData);
            this.player = proxySession;

            downstream.setPlayer(proxySession);
            this.session.setPlayer(proxySession);

            try {
                // String jwt = chainData.get(chainData.size() - 1);
                // JsonWebSignature jws = new JsonWebSignature();
                // jws.setCompactSerialization(jwt);
                // player.getLogger().saveJson("chainData", new JSONObject(JsonUtil.parseJson(jws.getUnverifiedPayload())));
                player.getLogger().saveJson("skinData", this.skinData);
            } catch (Exception e) {
                log.error("JSON output error: " + e.getMessage(), e);
            }
            // String authData = ForgeryUtils.forgeAuthData(proxySession.getProxyKeyPair(), extraData);
            String skinData = ForgeryUtils.forgeSkinData(proxySession.getProxyKeyPair(), this.skinData);
            // chainData.remove(chainData.size() - 1);
            // chainData.add(authData);

            LoginPacket login = new LoginPacket();
            // login.getChain().addAll(chainData);

            try {
                if (loginChain == null) {
                    initLoginChain();
                }
                log.info("Login chain: " + loginChain);
                loginChain.forEach(item -> {
                    login.getChain().add(item);
                });
            } catch (Exception e) {
                log.error("Failed to get login chain", e);
            }

            login.setExtra(skinData);
            login.setProtocolVersion(ProxyPass.PROTOCOL_VERSION);

            downstream.setPacketHandler(new DownstreamInitialPacketHandler(downstream, proxySession, this.proxy, login));
            downstream.setLogging(true);

            RequestNetworkSettingsPacket packet = new RequestNetworkSettingsPacket();
            packet.setProtocolVersion(ProxyPass.PROTOCOL_VERSION);
            downstream.sendPacketImmediately(packet);

            //SkinUtils.saveSkin(proxySession, this.skinData);
        });
    }

    private void initLoginChain() throws Exception {
        CloseableHttpClient client = MicrosoftConstants.createHttpClient();

        StepMCChain.MCChain mcChain = MinecraftAuth.BEDROCK_DEVICE_CODE_LOGIN.getFromInput(client, new StepMsaDeviceCode.MsaDeviceCodeCallback(msaDeviceCode -> {
            log.info("Go to " + msaDeviceCode.verificationUri());
            log.info("Enter code " + msaDeviceCode.userCode());

            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                try {
                    Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
                    clipboard.setContents(new StringSelection(msaDeviceCode.userCode()), null);
                    Desktop.getDesktop().browse(new URI(msaDeviceCode.verificationUri()));
                } catch (IOException | URISyntaxException e) {
                    log.error("Failed to open browser", e);
                }
            }
        }));

        String publicKey = Base64.getEncoder().encodeToString(mcChain.publicKey().getEncoded());

        GsonDeserializer<Map<String, ?>> gsonDeserializer = new GsonDeserializer<>(new GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).disableHtmlEscaping().create());
        Jws<Claims> mojangJwt = Jwts.parserBuilder().setAllowedClockSkewSeconds(60).setSigningKey(MOJANG_PUBLIC_KEY).deserializeJsonWith(gsonDeserializer).build().parseClaimsJws(mcChain.mojangJwt());

        String selfSignedJwt = Jwts.builder()
            .signWith(mcChain.privateKey(), SignatureAlgorithm.ES384)
            .setHeaderParam("x5u", publicKey)
            .claim("certificateAuthority", true)
            .claim("identityPublicKey", mojangJwt.getHeader().get("x5u"))
            .setExpiration(Date.from(Instant.now().plus(2, ChronoUnit.DAYS)))
            .setNotBefore(Date.from(Instant.now().minus(1, ChronoUnit.MINUTES)))
            .compact();

        loginChain = new ArrayList<>(List.of(selfSignedJwt, mcChain.mojangJwt(), mcChain.identityJwt()));
    }

}
