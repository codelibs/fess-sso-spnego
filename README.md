SPNEGO SSO Plugin for Fess
[![Java CI with Maven](https://github.com/codelibs/fess-sso-spnego/actions/workflows/maven.yml/badge.svg)](https://github.com/codelibs/fess-sso-spnego/actions/workflows/maven.yml)
==========================

Windows Integrated Authentication (SPNEGO/Kerberos) single sign-on for
[Fess](https://github.com/codelibs/fess).

This plugin provides the **SSO authenticator** behind `sso.type=spnego`: Fess answers an
unauthenticated request with a `WWW-Authenticate: Negotiate` challenge, accepts the Kerberos
service ticket the browser then sends, and takes the user name from the client principal. Groups
and roles are not carried by the ticket — they are read from LDAP afterwards, which is what makes
role-based search apply.

It was part of the Fess distribution until 15.9. It moved here together with the
[spnego](https://github.com/codelibs/spnego) library that performs the handshake.

## Installation

```
$ bin/fess-setup install plugin fess-sso-spnego
```

Or download the jar from [maven.codelibs.org](https://maven.codelibs.org/org/codelibs/fess/fess-sso-spnego/)
and put it in `app/WEB-INF/plugin`. Restart Fess afterwards: the components this plugin
contributes are read when the DI container is built.

## Configuration

Set these on the General page of the administration screen, or write them to
`app/WEB-INF/conf/system.properties` — the same file that screen saves to. None of these are
`fess_config.properties` keys.

**Unlike most Fess settings, the `spnego.*` keys have no `-Dfess.system.<key>` fallback.** They
are read straight from `systemProperties`, not through `FessConfig#getSystemProperty`, so a value
that exists only on the JVM command line is not seen. `sso.type` itself does honour
`-Dfess.system.sso.type`.

First select this authenticator:

| Key | Value |
| --- | --- |
| `sso.type` | `spnego` |

Then configure the Kerberos acceptor. A blank value counts as unset and falls back to the default
below, because the administration screen writes every key on save and clearing an input field
stores an empty string rather than removing the key:

| Key | Value |
| --- | --- |
| `spnego.krb5.conf` | classpath location of the Kerberos configuration (default `krb5.conf`) |
| `spnego.login.conf` | classpath location of the JAAS login configuration (default `auth_login.conf`) |
| `spnego.login.client.module` | the JAAS entry used as the initiator (default `spnego-client`) |
| `spnego.login.server.module` | the JAAS entry used as the acceptor (default `spnego-server`) |
| `spnego.preauth.username` | the service account name; empty means keytab login (default empty) |
| `spnego.preauth.password` | the service account password; empty means keytab login (default empty) |
| `spnego.allow.basic` | offer Basic authentication as a fallback (default `true`) |
| `spnego.allow.unsecure.basic` | offer Basic over plain HTTP as well (default `false`) |
| `spnego.prompt.ntlm` | fall back to Basic when the browser sends an NTLM token (default `true`) |
| `spnego.allow.localhost` | authenticate a same-host request as the server OS user (default `false`) |
| `spnego.allow.delegation` | keep the delegated credential when the client offers one (default `false`) |
| `spnego.allowed.realms` | Kerberos realms accepted besides the server realm, comma-separated (default empty) |
| `spnego.logger.level` | the library's `java.util.logging` level, `0`-`7`; derived from the Fess log level when blank |

Six things are worth knowing before the first login:

* **The two configuration files have to exist.** `spnego.krb5.conf` and `spnego.login.conf` name
  classpath resources, so the files go in `app/WEB-INF/classes/`. They are not shipped: a missing
  one fails the login with "SPNEGO configuration file not found", not the startup, because SPNEGO
  is initialized on the first login rather than at boot.
* **Every `spnego.*` change needs a restart.** The library keeps its parsed configuration in a
  JVM-wide singleton (`SpnegoFilterConfig`) and Fess builds it once, so nothing read here is
  revisited for the lifetime of the process. The same applies to the server credential: restart
  after the service account password is changed in AD or the keytab is replaced.
* **A keytab is used only when both pre-authentication fields are empty.** That is why clearing
  the password field on the administration screen *removes* the key instead of storing an empty
  string, which is how a keytab configuration stays reachable once a password has been saved. With
  exactly one of the two set and no keytab in the login configuration, initialization fails with
  "Must specify a username and password or a keyTab."
* **`spnego.allow.basic=false` also needs `spnego.prompt.ntlm=false`.** Prompting for NTLM means
  downgrading to Basic, so the library refuses the combination outright and SPNEGO never
  initializes.
* **`spnego.allow.unsecure.basic=false` means Basic is offered only when the request is secure.**
  The library asks `HttpServletRequest#isSecure()`, which is false when TLS is terminated at a
  reverse proxy and the request reaches Fess over HTTP — a client that cannot get a ticket then has
  no way in. Set `tomcat.secure=true` in `tomcat_config.properties` for that deployment.
* **A realm other than the server's own is refused unless it is listed.** List the child domains
  and trusted forests your users log in from in `spnego.allowed.realms`. Note that Fess identifies
  a user by the part of the principal before `@`, so two accounts with the same name in two listed
  realms are the same Fess user. When neither the server realm nor a list can be determined the
  realm is accepted and a warning is logged, which keeps an existing installation working but
  performs no check at all.

`spnego.exclude.dirs` is deliberately **not** honoured. Only `SpnegoHttpFilter` consumes it, and
Fess does not install that filter: it calls the library's `authenticate()` directly from the login
flow, so forwarding the key would advertise an exclusion that never happens.

See the [Windows Integrated Authentication documentation](https://fess.codelibs.org/stable/config/sso-spnego.html)
for the SPN registration, the `krb5.conf` and `auth_login.conf` contents, the LDAP settings that
resolve groups, and the browser configuration.

## Version

| Fess | Plugin |
| --- | --- |
| 15.9.x | 15.9.x |

Match the minor version. Installing this plugin into Fess 15.8 or earlier breaks SSO with a 500:
those versions register `spnegoAuthenticator` in their own `fess_sso++.xml`, and a second
registration under the same name from this plugin makes `getComponent()` fail with
`TooManyRegistrationComponentException`.

## How it plugs in

Nothing here is wired by class name from Fess. The plugin ships one additive LastaDi file that
Fess merges from every jar on the class path:

* `fess_sso++.xml` registers `spnegoAuthenticator`. `SsoManager.getAuthenticator()` resolves an
  authenticator as `<sso.type>Authenticator`, so the component name is what makes
  `sso.type=spnego` resolve. It is a singleton, because the authenticator registers that instance
  with `ssoManager` from its `init()` and disposes of the server credential from its `destroy()`.

The `SsoAuthenticator` interface, `SsoManager`, `SsoAction` and the SSO section of the
administration screen remain in Fess; only this authenticator, the credential it hands to
`FessLoginAssist`, and the library they compile against ship here. SPNEGO is not installed as a
servlet filter: the authenticator implements `FilterConfig` itself so that the library's
configuration can be built from the `spnego.*` keys. The library is shaded in without relocation,
because it keeps that configuration in a private static singleton and a relocated copy would be a
second, independent instance of it.
