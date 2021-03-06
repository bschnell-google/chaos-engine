/*
 *    Copyright (c) 2018 - 2020, Thales DIS CPL Canada, Inc
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 *
 */

package com.thales.chaos.notification.services;

import eu.geekplace.javapinning.JavaPinning;
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration;
import org.jxmpp.jid.EntityBareJid;
import org.jxmpp.jid.impl.JidCreate;
import org.jxmpp.stringprep.XmppStringprepException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.net.ssl.SSLContext;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.stream.Collectors;

@Configuration
@ConfigurationProperties(prefix = "xmpp")
@ConditionalOnProperty(name = "xmpp.enabled", havingValue = "true")
public class XMPPNotificationService {
    private String user;
    private String password;
    private String domain;
    private String hostname;
    private String serverCertFingerprint;
    private String conferenceRooms;
    private String recipients;

    public void setUser (String user) {
        this.user = user;
    }

    public void setPassword (String password) {
        this.password = password;
    }

    public void setHostname (String hostname) {
        this.hostname = hostname;
    }

    public void setServerCertFingerprint (String serverCertFingerprint) {
        this.serverCertFingerprint = serverCertFingerprint;
    }

    public void setDomain (String domain) {
        this.domain = domain;
    }

    public void setConferenceRooms (String conferenceRooms) {
        this.conferenceRooms = conferenceRooms;
    }

    public void setRecipients (String recipients) {
        this.recipients = recipients;
    }

    @Bean
    @RefreshScope
    AddressBook getAddressBook () {
        return new AddressBook(recipients, conferenceRooms);
    }

    @Bean
    @RefreshScope
    XMPPConfiguration getConfiguration () {
        return new XMPPConfiguration();
    }

    public class XMPPConfiguration {
        public XMPPTCPConnectionConfiguration get () throws XmppStringprepException, KeyManagementException, NoSuchAlgorithmException {
            return XMPPTCPConnectionConfiguration.builder()
                                                 .setUsernameAndPassword(user, password)
                                                 .setXmppDomain(domain)
                                                 .setHost(hostname)
                                                 .setCustomSSLContext(getSecurityContext())
                                                 .build();
        }
    }

    SSLContext getSecurityContext () throws NoSuchAlgorithmException, KeyManagementException {
        if (serverCertFingerprint != null && !serverCertFingerprint.isBlank()) {
            return JavaPinning.forPin(serverCertFingerprint);
        }
        return SSLContext.getDefault();
    }

    public static class AddressBook {
        private Collection<EntityBareJid> recipients;
        private Collection<EntityBareJid> conferenceRooms;

        public AddressBook (String recipients, String conferenceRooms) {
            this.recipients = parse(recipients);
            this.conferenceRooms = parse(conferenceRooms);
        }

        private Collection<EntityBareJid> parse (String jids) {
            if (jids == null || jids.isBlank()) {
                return Collections.emptySet();
            }
            return Arrays.stream(jids.split(","))
                         .map(JidCreate::entityBareFromOrNull)
                         .filter(Objects::nonNull)
                         .collect(Collectors.toList());
        }

        public Collection<EntityBareJid> getRecipients () {
            return recipients;
        }

        public Collection<EntityBareJid> getConferenceRooms () {
            return conferenceRooms;
        }
    }
}
