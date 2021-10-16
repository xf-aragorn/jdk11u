/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package sun.security.ssl;

import javax.crypto.spec.DHParameterSpec;
import javax.net.ssl.SSLException;
import java.io.IOException;
import java.security.*;
import java.security.spec.*;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import javax.crypto.KeyAgreement;
import sun.security.ssl.DHKeyExchange.DHEPossession;
import sun.security.ssl.ECDHKeyExchange.ECDHEPossession;
import sun.security.util.CurveDB;


/**
 * An enum containing all known named groups for use in TLS.
 *
 * The enum also contains the required properties of each group and the
 * required functions (e.g. encoding/decoding).
 */
enum NamedGroup {
    // Elliptic Curves (RFC 4492)
    //
    // See sun.security.util.CurveDB for the OIDs
    // NIST P-224
    SECP256_K1(0x0016, "secp256k1", false,
            NamedGroupSpec.NAMED_GROUP_ECDHE,
            ProtocolVersion.PROTOCOLS_TO_12,
            CurveDB.lookup("secp256k1")),

    // NIST P-256
    SECP256_R1(0x0017, "secp256r1", true,
            NamedGroupSpec.NAMED_GROUP_ECDHE,
            ProtocolVersion.PROTOCOLS_TO_13,
            CurveDB.lookup("secp256r1")),

    // NIST P-384
    SECP384_R1(0x0018, "secp384r1", true,
            NamedGroupSpec.NAMED_GROUP_ECDHE,
            ProtocolVersion.PROTOCOLS_TO_13,
            CurveDB.lookup("secp384r1")),

    // NIST P-521
    SECP521_R1(0x0019, "secp521r1", true,
            NamedGroupSpec.NAMED_GROUP_ECDHE,
            ProtocolVersion.PROTOCOLS_TO_13,
            CurveDB.lookup("secp521r1")),

    // x25519 and x448 (RFC 8422/8446)
    X25519(0x001D, "x25519", true,
            NamedGroupSpec.NAMED_GROUP_XDH,
            ProtocolVersion.PROTOCOLS_TO_13,
            NamedParameterSpec.X25519),
    X448(0x001E, "x448", true,
            NamedGroupSpec.NAMED_GROUP_XDH,
            ProtocolVersion.PROTOCOLS_TO_13,
            NamedParameterSpec.X448),

    // Finite Field Diffie-Hellman Ephemeral Parameters (RFC 7919)
    FFDHE_2048(0x0100, "ffdhe2048", true,
            NamedGroupSpec.NAMED_GROUP_FFDHE,
            ProtocolVersion.PROTOCOLS_TO_13,
            PredefinedDHParameterSpecs.ffdheParams.get(2048)),

    FFDHE_3072(0x0101, "ffdhe3072", true,
            NamedGroupSpec.NAMED_GROUP_FFDHE,
            ProtocolVersion.PROTOCOLS_TO_13,
            PredefinedDHParameterSpecs.ffdheParams.get(3072)),
    FFDHE_4096(0x0102, "ffdhe4096", true,
            NamedGroupSpec.NAMED_GROUP_FFDHE,
            ProtocolVersion.PROTOCOLS_TO_13,
            PredefinedDHParameterSpecs.ffdheParams.get(4096)),
    FFDHE_6144(0x0103, "ffdhe6144", true,
            NamedGroupSpec.NAMED_GROUP_FFDHE,
            ProtocolVersion.PROTOCOLS_TO_13,
            PredefinedDHParameterSpecs.ffdheParams.get(6144)),
    FFDHE_8192(0x0104, "ffdhe8192", true,
            NamedGroupSpec.NAMED_GROUP_FFDHE,
            ProtocolVersion.PROTOCOLS_TO_13,
	    PredefinedDHParameterSpecs.ffdheParams.get(8192));

    final int id;               // hash + signature
    final String name;          // literal name
    final boolean isFips;       // can be used in FIPS mode?
    final NamedGroupSpec spec;  // group type
    final ProtocolVersion[] supportedProtocols;
    final String algorithm;     // key exchange algorithm
    final AlgorithmParameterSpec keAlgParamSpec;
    final AlgorithmParameters keAlgParams;
    final boolean isAvailable;

    // performance optimization
    private static final Set<CryptoPrimitive> KEY_AGREEMENT_PRIMITIVE_SET =
        Collections.unmodifiableSet(EnumSet.of(CryptoPrimitive.KEY_AGREEMENT));

    // Constructor used for all NamedGroup types
    private NamedGroup(int id, String name, boolean isFips,
            NamedGroupSpec namedGroupSpec,
            ProtocolVersion[] supportedProtocols,
            AlgorithmParameterSpec keAlgParamSpec) {
        this.id = id;
        this.name = name;
        this.isFips = isFips;
        this.spec = namedGroupSpec;
        this.algorithm = namedGroupSpec.algorithm;
        this.supportedProtocols = supportedProtocols;
        this.keAlgParamSpec = keAlgParamSpec;

        AlgorithmParameters algParams = null;
        boolean mediator = (keAlgParamSpec != null);
        if (mediator) {
            try {
                algParams =
                    AlgorithmParameters.getInstance(namedGroupSpec.algorithm);
                algParams.init(keAlgParamSpec);
            } catch (InvalidParameterSpecException
                    | NoSuchAlgorithmException exp) {
                if (namedGroupSpec != NamedGroupSpec.NAMED_GROUP_XDH) {
                    mediator = false;
                    if (SSLLogger.isOn && SSLLogger.isOn("ssl,handshake")) {
                        SSLLogger.warning(
                            "No AlgorithmParameters for " + name, exp);
                    }
                } else {
                    // HACK CODE
                    //
                    // Please remove the following code if the XDH/X25519/X448
                    // AlgorithmParameters algorithms are supported in JDK.
                    algParams = null;
                    try {
                        KeyAgreement.getInstance(name);

                        // The following service is also needed.  But for
                        // performance, check the KeyAgreement impl only.
                        //
                        // KeyFactory.getInstance(name);
                        // KeyPairGenerator.getInstance(name);
                        // AlgorithmParameters.getInstance(name);
                    } catch (NoSuchAlgorithmException nsae) {
                        mediator = false;
                        if (SSLLogger.isOn && SSLLogger.isOn("ssl,handshake")) {
                            SSLLogger.warning(
                                "No AlgorithmParameters for " + name, nsae);
                        }
                    }
                }
            }
        }

        this.isAvailable = mediator;
        this.keAlgParams = mediator ? algParams : null;
    }

    //
    // The next set of methods search & retrieve NamedGroups.
    //
    static NamedGroup valueOf(int id) {
        for (NamedGroup group : NamedGroup.values()) {
            if (group.id == id) {
                return group;
            }
        }

        return null;
    }

    static NamedGroup valueOf(ECParameterSpec params) {
        for (NamedGroup ng : NamedGroup.values()) {
            if (ng.spec == NamedGroupSpec.NAMED_GROUP_ECDHE) {
                if ((params == ng.keAlgParamSpec) ||
                        (ng.keAlgParamSpec == CurveDB.lookup(params))) {
                    return ng;
                }
            }
        }

        return null;
    }

    static NamedGroup valueOf(DHParameterSpec params) {
        for (NamedGroup ng : NamedGroup.values()) {
            if (ng.spec != NamedGroupSpec.NAMED_GROUP_FFDHE) {
                continue;
            }

            DHParameterSpec ngParams = (DHParameterSpec)ng.keAlgParamSpec;
            if (ngParams.getP().equals(params.getP())
                    && ngParams.getG().equals(params.getG())) {
                return ng;
            }
        }

        return null;
    }

    static NamedGroup nameOf(String name) {
        for (NamedGroup group : NamedGroup.values()) {
            if (group.name.equals(name)) {
                return group;
            }
        }

        return null;
    }

    static String nameOf(int id) {
        for (NamedGroup group : NamedGroup.values()) {
            if (group.id == id) {
                return group.name;
            }
        }

        return "UNDEFINED-NAMED-GROUP(" + id + ")";
    }

    // Is the NamedGroup available for the protocols desired?
    boolean isAvailable(List<ProtocolVersion> protocolVersions) {
        if (this.isAvailable) {
            for (ProtocolVersion pv : supportedProtocols) {
                if (protocolVersions.contains(pv)) {
                    return true;
                }
            }
        }

        return false;
    }

    boolean isAvailable(ProtocolVersion protocolVersion) {
        if (this.isAvailable) {
            for (ProtocolVersion pv : supportedProtocols) {
                if (protocolVersion == pv) {
                    return true;
                }
            }
        }

        return false;
    }

    // Are the NamedGroups available for the ciphersuites desired?
    boolean isSupported(List<CipherSuite> cipherSuites) {
        for (CipherSuite cs : cipherSuites) {
            boolean isMatch = isAvailable(cs.supportedProtocols);
            if (isMatch && ((cs.keyExchange == null)
                    || (NamedGroupSpec.arrayContains(
                            cs.keyExchange.groupTypes, spec)))) {
                return true;
            }
        }

        return false;
    }

    boolean isPermitted(AlgorithmConstraints constraints) {
        return constraints.permits(KEY_AGREEMENT_PRIMITIVE_SET,
                        this.name, null) &&
                constraints.permits(KEY_AGREEMENT_PRIMITIVE_SET,
                        this.algorithm, this.keAlgParams);
    }

    byte[] encodePossessionPublicKey(
            NamedGroupPossession namedGroupPossession) {
        return spec.encodePossessionPublicKey(namedGroupPossession);
    }

    SSLCredentials decodeCredentials(
            byte[] encoded) throws IOException, GeneralSecurityException {
        return spec.decodeCredentials(this, encoded);
    }

    SSLPossession createPossession(SecureRandom random) {
        return spec.createPossession(this, random);
    }

    SSLKeyDerivation createKeyDerivation(
            HandshakeContext hc) throws IOException {
        return spec.createKeyDerivation(hc);
    }

    // A list of operations related to named groups.
    private interface NamedGroupScheme {
        byte[] encodePossessionPublicKey(
                NamedGroupPossession namedGroupPossession);

        SSLCredentials decodeCredentials(NamedGroup ng,
                byte[] encoded) throws IOException, GeneralSecurityException;

        SSLPossession createPossession(NamedGroup ng, SecureRandom random);

        SSLKeyDerivation createKeyDerivation(
                HandshakeContext hc) throws IOException;
    }

    enum NamedGroupSpec implements NamedGroupScheme {
        // Elliptic Curve Groups (ECDHE)
        NAMED_GROUP_ECDHE("EC", ECDHEScheme.instance),

        // Finite Field Groups (DHE)
        NAMED_GROUP_FFDHE("DiffieHellman", FFDHEScheme.instance),

        // Finite Field Groups (XDH)
        NAMED_GROUP_XDH("XDH", XDHScheme.instance),

        // arbitrary prime and curves (ECDHE)
        NAMED_GROUP_ARBITRARY("EC", null),

        // Not predefined named group
        NAMED_GROUP_NONE("", null);

        private final String algorithm;     // key exchange name
        private final NamedGroupScheme scheme;  // named group operations

        private NamedGroupSpec(String algorithm, NamedGroupScheme scheme) {
            this.algorithm = algorithm;
            this.scheme = scheme;
        }

        boolean isSupported(List<CipherSuite> cipherSuites) {
            for (CipherSuite cs : cipherSuites) {
                if (cs.keyExchange == null ||
                        arrayContains(cs.keyExchange.groupTypes, this)) {
                    return true;
                }
            }

            return false;
        }

        static boolean arrayContains(NamedGroupSpec[] namedGroupTypes,
                NamedGroupSpec namedGroupType) {
            for (NamedGroupSpec ng : namedGroupTypes) {
                if (ng == namedGroupType) {
                    return true;
                }
            }

            return false;
        }

        @Override
        public byte[] encodePossessionPublicKey(
                NamedGroupPossession namedGroupPossession) {
            if (scheme != null) {
                return scheme.encodePossessionPublicKey(namedGroupPossession);
            }

            return null;
        }

        @Override
        public SSLCredentials decodeCredentials(NamedGroup ng,
                byte[] encoded) throws IOException, GeneralSecurityException {
            if (scheme != null) {
                return scheme.decodeCredentials(ng, encoded);
            }

            return null;
        }

        @Override
        public SSLPossession createPossession(
                NamedGroup ng, SecureRandom random) {
            if (scheme != null) {
                return scheme.createPossession(ng, random);
            }

            return null;
        }

        @Override
        public SSLKeyDerivation createKeyDerivation(
                HandshakeContext hc) throws IOException {
            if (scheme != null) {
                return scheme.createKeyDerivation(hc);
            }

            return null;
        }
    }

    private static class FFDHEScheme implements NamedGroupScheme {
        private static final FFDHEScheme instance = new FFDHEScheme();

        @Override
        public byte[] encodePossessionPublicKey(
                NamedGroupPossession namedGroupPossession) {
            return ((DHEPossession)namedGroupPossession).encode();
        }

        @Override
        public SSLCredentials decodeCredentials(NamedGroup ng,
                byte[] encoded) throws IOException, GeneralSecurityException {
            return DHKeyExchange.DHECredentials.valueOf(ng, encoded);
        }

        @Override
        public SSLPossession createPossession(
                NamedGroup ng, SecureRandom random) {
            return new DHKeyExchange.DHEPossession(ng, random);
        }

        @Override
        public SSLKeyDerivation createKeyDerivation(
                HandshakeContext hc) throws IOException {

            return DHKeyExchange.kaGenerator.createKeyDerivation(hc);
        }
    }

    private static class ECDHEScheme implements NamedGroupScheme {
        private static final ECDHEScheme instance = new ECDHEScheme();

        @Override
        public byte[] encodePossessionPublicKey(
                NamedGroupPossession namedGroupPossession) {
            return ((ECDHEPossession)namedGroupPossession).encode();
        }

        @Override
        public SSLCredentials decodeCredentials(NamedGroup ng,
                byte[] encoded) throws IOException, GeneralSecurityException {
            return ECDHKeyExchange.ECDHECredentials.valueOf(ng, encoded);
        }

        @Override
        public SSLPossession createPossession(
                NamedGroup ng, SecureRandom random) {
            return new ECDHKeyExchange.ECDHEPossession(ng, random);
        }

        @Override
        public SSLKeyDerivation createKeyDerivation(
                HandshakeContext hc) throws IOException {
            return ECDHKeyExchange.ecdheKAGenerator.createKeyDerivation(hc);
        }
    }

    private static class XDHScheme implements NamedGroupScheme {
        private static final XDHScheme instance = new XDHScheme();

        @Override
        public byte[] encodePossessionPublicKey(NamedGroupPossession poss) {
            return ((XDHKeyExchange.XDHEPossession)poss).encode();
        }

        @Override
        public SSLCredentials decodeCredentials(NamedGroup ng,
                byte[] encoded) throws IOException, GeneralSecurityException {
            return XDHKeyExchange.XDHECredentials.valueOf(ng, encoded);
        }

        @Override
        public SSLPossession createPossession(
                NamedGroup ng, SecureRandom random) {
            return new XDHKeyExchange.XDHEPossession(ng, random);
        }

        @Override
        public SSLKeyDerivation createKeyDerivation(
                HandshakeContext hc) throws IOException {
            return XDHKeyExchange.xdheKAGenerator.createKeyDerivation(hc);
        }
    }
}
