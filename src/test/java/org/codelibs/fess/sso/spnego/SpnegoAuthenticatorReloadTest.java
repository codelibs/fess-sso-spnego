/*
 * Copyright 2012-2025 CodeLibs Project and the Others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */
package org.codelibs.fess.sso.spnego;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.security.auth.kerberos.KerberosPrincipal;
import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.login.Configuration;
import javax.security.auth.login.LoginContext;

import org.codelibs.core.misc.DynamicProperties;
import org.codelibs.fess.exception.SsoLoginException;
import org.codelibs.fess.unit.UnitFessTestCase;
import org.codelibs.fess.util.ComponentUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

/**
 * Builds real library authenticators from the {@code spnego.*} system properties and checks that a
 * changed setting takes effect without a restart.
 *
 * <p>
 * The JAAS fixture configures an acceptor that uses a keytab, which logs in without a KDC and
 * without reading the keytab, so the whole initialization runs here: the JAAS login configuration
 * is read, and the server login reloads the Kerberos configuration. The JVM-wide state this touches
 * is saved and restored by this class.
 * </p>
 */
public class SpnegoAuthenticatorReloadTest extends UnitFessTestCase {

    private static final String JAAS_CONFIG_PROPERTY = "java.security.auth.login.config";

    private static final String KRB5_CONFIG_PROPERTY = "java.security.krb5.conf";

    private Configuration savedJaasConfiguration;

    private String savedKrb5ConfigProperty;

    private String savedJaasConfigProperty;

    private RecordingAuthenticator authenticator;

    @Override
    protected void setUp(final TestInfo testInfo) throws Exception {
        super.setUp(testInfo);
        try {
            savedJaasConfiguration = Configuration.getConfiguration();
        } catch (final Exception | Error e) {
            savedJaasConfiguration = null;
        }
        // A refresh only rereads the file when the configuration is the JDK's file based one.
        Configuration.setConfiguration(null);
        savedKrb5ConfigProperty = System.getProperty(KRB5_CONFIG_PROPERTY);
        savedJaasConfigProperty = System.getProperty(JAAS_CONFIG_PROPERTY);

        final DynamicProperties systemProperties = ComponentUtil.getSystemProperties();
        systemProperties.setProperty(SpnegoAuthenticator.SPNEGO_LOGIN_CONF, "spnego/test_auth_login.conf");
        systemProperties.setProperty(SpnegoAuthenticator.SPNEGO_KRB5_CONF, "spnego/test_krb5.conf");
        authenticator = new RecordingAuthenticator();
    }

    @Override
    protected void tearDown(final TestInfo testInfo) throws Exception {
        try {
            authenticator.destroy();
            final DynamicProperties systemProperties = ComponentUtil.getSystemProperties();
            for (final String key : SpnegoAuthenticator.AUTHENTICATOR_SETTING_KEYS) {
                systemProperties.remove(key);
            }
            restoreSystemProperty(JAAS_CONFIG_PROPERTY, savedJaasConfigProperty);
            restoreSystemProperty(KRB5_CONFIG_PROPERTY, savedKrb5ConfigProperty);
            reloadKrb5Config();
            Configuration.setConfiguration(savedJaasConfiguration);
        } finally {
            super.tearDown(testInfo);
        }
    }

    @Test
    public void test_unchangedSettings_keepTheAuthenticator() {
        final org.codelibs.spnego.SpnegoAuthenticator first = authenticator.getAuthenticator();

        assertSame(first, authenticator.getAuthenticator());
        assertEquals(1, authenticator.created.size());
        assertTrue(authenticator.disposed.isEmpty());
        assertEquals("TEST.EXAMPLE", first.getServerRealm());
    }

    @Test
    public void test_blankSetting_countsAsUnset() {
        final org.codelibs.spnego.SpnegoAuthenticator first = authenticator.getAuthenticator();

        // The administration screen stores an empty string for a cleared field.
        ComponentUtil.getSystemProperties().setProperty(SpnegoAuthenticator.SPNEGO_ALLOW_LOCALHOST, "");

        assertSame(first, authenticator.getAuthenticator());
        assertEquals(1, authenticator.created.size());
    }

    @Test
    public void test_changedSetting_rebuildsAndDisposesTheOldAuthenticator() {
        final org.codelibs.spnego.SpnegoAuthenticator first = authenticator.getAuthenticator();

        ComponentUtil.getSystemProperties().setProperty(SpnegoAuthenticator.SPNEGO_ALLOW_BASIC, "false");
        ComponentUtil.getSystemProperties().setProperty(SpnegoAuthenticator.SPNEGO_PROMPT_NTLM, "false");

        final org.codelibs.spnego.SpnegoAuthenticator second = authenticator.getAuthenticator();
        assertNotSame(first, second);
        assertEquals(2, authenticator.created.size());
        assertEquals(List.of(first), authenticator.disposed);
        assertSame(second, authenticator.getAuthenticator());
    }

    @Test
    public void test_eachSettingKey_triggersARebuild() {
        authenticator.getAuthenticator();
        final DynamicProperties systemProperties = ComponentUtil.getSystemProperties();
        for (final String key : SpnegoAuthenticator.AUTHENTICATOR_SETTING_KEYS) {
            final Map<String, String> before = authenticator.getAuthenticatorSettings();
            final String saved = systemProperties.getProperty(key);
            systemProperties.setProperty(key, "changed");
            assertFalse(before.equals(authenticator.getAuthenticatorSettings()), key);
            if (saved == null) {
                systemProperties.remove(key);
            } else {
                systemProperties.setProperty(key, saved);
            }
        }
        // spnego.allowed.realms is read on every login and must not rebuild anything.
        final Map<String, String> before = authenticator.getAuthenticatorSettings();
        systemProperties.setProperty(SpnegoAuthenticator.SPNEGO_ALLOWED_REALMS, "PARTNER.EXAMPLE");
        try {
            assertEquals(before, authenticator.getAuthenticatorSettings());
        } finally {
            systemProperties.remove(SpnegoAuthenticator.SPNEGO_ALLOWED_REALMS);
        }
    }

    @Test
    public void test_changedKrb5Conf_reloadsTheKerberosConfiguration() {
        authenticator.getAuthenticator();
        assertEquals("TEST.EXAMPLE", new KerberosPrincipal("user").getRealm());

        ComponentUtil.getSystemProperties().setProperty(SpnegoAuthenticator.SPNEGO_KRB5_CONF, "spnego/test_krb5_other.conf");
        authenticator.getAuthenticator();

        assertEquals(2, authenticator.created.size());
        assertTrue(System.getProperty(KRB5_CONFIG_PROPERTY).endsWith("test_krb5_other.conf"));
        assertEquals("OTHER.EXAMPLE", new KerberosPrincipal("user").getRealm());
    }

    @Test
    public void test_failedRebuild_leavesNoAuthenticatorAndRecovers() {
        final org.codelibs.spnego.SpnegoAuthenticator first = authenticator.getAuthenticator();

        ComponentUtil.getSystemProperties().setProperty(SpnegoAuthenticator.SPNEGO_LOGIN_SERVER_MODULE, "no-such-module");
        final SsoLoginException e = assertThrows(SsoLoginException.class, authenticator::getAuthenticator);
        assertTrue(e.getCause().getMessage().contains("no-such-module"));
        // The old authenticator no longer matches the settings, so it must not keep serving logins.
        assertEquals(List.of(first), authenticator.disposed);
        assertNull(authenticator.authenticator, "a failed rebuild must not leave an authenticator");

        ComponentUtil.getSystemProperties().remove(SpnegoAuthenticator.SPNEGO_LOGIN_SERVER_MODULE);
        final org.codelibs.spnego.SpnegoAuthenticator recovered = authenticator.getAuthenticator();
        assertNotSame(first, recovered);
        assertSame(recovered, authenticator.getAuthenticator());
    }

    /**
     * Loads the Kerberos configuration the restored system property names, with the same JDK
     * option the server login relies on, so that later tests do not see a fixture's realm.
     *
     * @throws Exception if the reload fails
     */
    private static void reloadKrb5Config() throws Exception {
        final Map<String, Object> options = new HashMap<>();
        options.put("refreshKrb5Config", "true");
        options.put("storeKey", "true");
        options.put("useKeyTab", "true");
        options.put("isInitiator", "false");
        options.put("doNotPrompt", "true");
        options.put("keyTab", "file:///nonexistent/fess-test.keytab");
        options.put("principal", "HTTP/localhost@RESTORE.EXAMPLE");
        final LoginContext context = new LoginContext("restore", null, null, new Configuration() {
            @Override
            public AppConfigurationEntry[] getAppConfigurationEntry(final String name) {
                return new AppConfigurationEntry[] { new AppConfigurationEntry("com.sun.security.auth.module.Krb5LoginModule",
                        AppConfigurationEntry.LoginModuleControlFlag.REQUIRED, options) };
            }
        });
        context.login();
        context.logout();
    }

    private static void restoreSystemProperty(final String key, final String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    /** Records the library authenticators it builds and disposes of. */
    private static class RecordingAuthenticator extends SpnegoAuthenticator {

        private final List<org.codelibs.spnego.SpnegoAuthenticator> created = new ArrayList<>();

        private final List<org.codelibs.spnego.SpnegoAuthenticator> disposed = new ArrayList<>();

        @Override
        protected org.codelibs.spnego.SpnegoAuthenticator createAuthenticator(final SpnegoConfig config) throws Exception {
            final org.codelibs.spnego.SpnegoAuthenticator result = super.createAuthenticator(config);
            created.add(result);
            return result;
        }

        @Override
        protected void disposeAuthenticator(final org.codelibs.spnego.SpnegoAuthenticator target) {
            disposed.add(target);
            super.disposeAuthenticator(target);
        }
    }
}
