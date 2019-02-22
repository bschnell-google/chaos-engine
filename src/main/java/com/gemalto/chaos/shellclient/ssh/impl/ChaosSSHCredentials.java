package com.gemalto.chaos.shellclient.ssh.impl;

import com.gemalto.chaos.ChaosException;
import com.gemalto.chaos.shellclient.ssh.SSHCredentials;
import net.schmizz.sshj.userauth.keyprovider.KeyPairWrapper;
import net.schmizz.sshj.userauth.method.AuthMethod;
import net.schmizz.sshj.userauth.method.AuthPassword;
import net.schmizz.sshj.userauth.method.AuthPublickey;
import net.schmizz.sshj.userauth.password.PasswordFinder;
import net.schmizz.sshj.userauth.password.Resource;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Security;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

public class ChaosSSHCredentials implements SSHCredentials {
    private static final Logger log = LoggerFactory.getLogger(ChaosSSHCredentials.class);

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    private final Map<PublicKey, PrivateKey> sshKeys = new HashMap<>();
    private String username;
    private Callable<String> passwordGenerator;

    @Override
    public String getUsername () {
        return username;
    }

    @Override
    public Callable<String> getPasswordGenerator () {
        return passwordGenerator;
    }

    @Override
    public Map<PublicKey, PrivateKey> getSSHKeys () {
        return sshKeys;
    }

    @Override
    public SSHCredentials withUsername (String username) {
        this.username = username;
        return this;
    }

    @Override
    public SSHCredentials withPasswordGenerator (Callable<String> passwordGenerator) {
        this.passwordGenerator = passwordGenerator;
        return this;
    }

    @Override
    public SSHCredentials withKeyPair (String privateKey, String publicKey) {
        PrivateKey privKey = SSHCredentials.privateKeyFromString(privateKey);
        return withKeyPair(privKey, SSHCredentials.publicKeyFromPrivateKey(privKey, publicKey));
    }

    @Override
    public SSHCredentials withKeyPair (PrivateKey privateKey, PublicKey publicKey) {
        sshKeys.put(publicKey, privateKey);
        return this;
    }

    @Override
    public List<AuthMethod> getAuthMethods () {
        List<AuthMethod> authMethods = sshKeys.entrySet()
                                              .stream()
                                              .filter(e -> e.getKey() != null && e.getValue() != null)
                                              .map(e -> new KeyPairWrapper(e.getKey(), e.getValue()))
                                              .map(AuthPublickey::new)
                                              .collect(Collectors.toList());
        if (passwordGenerator != null) {
            try {
                authMethods.add(new AuthPassword(new PasswordFinder() {
                    @Override
                    public char[] reqPassword (Resource<?> resource) {
                        try {
                            return passwordGenerator.call().toCharArray();
                        } catch (Exception e) {
                            throw new ChaosException(e);
                        }
                    }

                    @Override
                    public boolean shouldRetry (Resource<?> resource) {
                        return false;
                    }
                }));
            } catch (ChaosException e) {
                log.error("Could not create password-based authentication token");
            }
        }
        return authMethods;
    }
}
