package com.sap.cloud.security.test;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static com.sap.cloud.security.xsuaa.client.OidcConfigurationService.DISCOVERY_ENDPOINT_DEFAULT;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;
import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.Servlet;

import org.apache.commons.io.IOUtils;
import org.eclipse.jetty.annotations.AnnotationConfiguration;
import org.eclipse.jetty.plus.webapp.EnvConfiguration;
import org.eclipse.jetty.plus.webapp.PlusConfiguration;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.webapp.Configuration;
import org.eclipse.jetty.webapp.FragmentConfiguration;
import org.eclipse.jetty.webapp.MetaInfConfiguration;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.webapp.WebInfConfiguration;
import org.eclipse.jetty.webapp.WebXmlConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.sap.cloud.security.config.Service;
import com.sap.cloud.security.test.jetty.JettyTokenAuthenticator;
import com.sap.cloud.security.token.Token;
import com.sap.cloud.security.token.TokenClaims;
import com.sap.cloud.security.token.TokenHeader;
import com.sap.cloud.security.xsuaa.client.OAuth2ServiceEndpointsProvider;
import com.sap.cloud.security.xsuaa.client.OAuth2TokenServiceConstants;
import com.sap.cloud.security.xsuaa.client.XsuaaDefaultEndpoints;

public class SecurityTestInitializer {
	private final Logger logger = LoggerFactory.getLogger(SecurityTestInitializer.class);

	public static final String DEFAULT_APP_ID = "xsapp!t0815";
	public static final String DEFAULT_CLIENT_ID = "sb-clientId!t0815";
	public static final String DEFAULT_DOMAIN = "localhost";
	private static final Logger LOGGER = LoggerFactory.getLogger(SecurityTestInitializer.class);
	private static final String LOCALHOST_PATTERN = "http://localhost:%d";
	private final Map<String, ServletHolder> applicationServletsByPath = new HashMap<>();
	private final List<FilterHolder> applicationServletFilters = new ArrayList<>();
	// app server
	private Server applicationServer;
	private ApplicationServerOptions applicationServerOptions;
	private boolean useApplicationServer;

	// mock server
	private WireMockServer wireMockServer;
	private RSAKeys keys;
	private int wireMockPort = 0;
	private Service service;

	private String clientId = DEFAULT_CLIENT_ID;
	private String jwksUrl;

	public SecurityTestInitializer(Service service) {
		this(service, false);
	}

	public SecurityTestInitializer(Service service, boolean useApplicationServer) {
		this.service = service;
		this.useApplicationServer = useApplicationServer;
		keys = RSAKeys.generate();

		if (useApplicationServer) {
			try {
				startApplicationServer();
			} catch (Exception e) {
				logger.error("initialization of the application server failed!");
			}
		}

		setupWireMock();

	}

	/**
	 * Specifies an embedded jetty as servlet server. It needs to be configured
	 * before the {@link #before()} method. The application server will be started
	 * with default options for the given {@link Service}, see
	 * {@link ApplicationServerOptions#forService(Service)} for details. By default
	 * the servlet server will listen on a free random port. Use
	 * {@link SecurityTestInitializer#useApplicationServer(ApplicationServerOptions)}
	 * to overwrite default settings. Use {@link #getApplicationServerUri()} to
	 * obtain the actual port used at runtime.
	 *
	 * @return the rule itself.
	 */
	public SecurityTestInitializer useApplicationServer() {
		return useApplicationServer(ApplicationServerOptions.forService(service));
	}

	/**
	 * Specifies an embedded jetty as servlet server. It needs to be configured
	 * before the {@link #before()} method. Use
	 * {@link ApplicationServerOptions#forService(Service)} to obtain a
	 * configuration object that can be customized. See
	 * {@link ApplicationServerOptions} for details.
	 *
	 * @param applicationServerOptions custom options to configure the application
	 *                                 server.
	 * @return the rule itself.
	 */
	public SecurityTestInitializer useApplicationServer(ApplicationServerOptions applicationServerOptions) {
		this.applicationServerOptions = applicationServerOptions;
		useApplicationServer = true;
		return this;
	}

	/**
	 * Adds a servlet to the servlet server. Only has an effect when used in
	 * conjunction with {@link #useApplicationServer}.
	 *
	 * @param servletClass the servlet class that should be served.
	 * @param path         the path on which the servlet should be served, e.g.
	 *                     "/*".
	 * @return the rule itself.
	 */
	public SecurityTestInitializer addApplicationServlet(Class<? extends Servlet> servletClass, String path) {
		applicationServletsByPath.put(path, new ServletHolder(servletClass));
		return this;
	}

	/**
	 * Adds a servlet to the servlet server. Only has an effect when used in
	 * conjunction with {@link #useApplicationServer}.
	 *
	 * @param servletHolder the servlet inside a {@link ServletHolder} that should
	 *                      be served.
	 * @param path          the path on which the servlet should be served, e.g.
	 *                      "/*".
	 * @return the rule itself.
	 */
	public SecurityTestInitializer addApplicationServlet(ServletHolder servletHolder, String path) {
		applicationServletsByPath.put(path, servletHolder);
		return this;
	}

	/**
	 * Adds a filter to the servlet server. Only has an effect when used in
	 * conjunction with {@link #useApplicationServer}.
	 *
	 * @param filterClass the filter class that should intercept with incoming
	 *                    requests.
	 * @return the rule itself.
	 */
	public SecurityTestInitializer addApplicationServletFilter(Class<? extends Filter> filterClass) {
		applicationServletFilters.add(new FilterHolder(filterClass));
		return this;
	}

	/**
	 * Overwrites the port on which the identity service mock server runs
	 * (WireMock). It needs to be configured before the {@link #before()} method. If
	 * the port is not specified or is set to 0, a free random port is chosen.
	 *
	 * @param port the port on which the wire mock service is started.
	 * @return the rule itself.
	 */
	public SecurityTestInitializer setPort(int port) {
		this.wireMockPort = port;
		return this;
	}

	/**
	 * Overwrites the private/public key pair to be used. The private key is used to
	 * sign the jwt token. The public key is provided by jwks endpoint (on behalf of
	 * WireMock). Checked exceptions are caught and rethrown as runtime exceptions
	 * for test convenience.
	 *
	 * @param publicKeyPath  resource path to public key file.
	 * @param privateKeyPath resource path to private key file.
	 * @return the rule itself.
	 */
	public SecurityTestInitializer setKeys(String publicKeyPath, String privateKeyPath) {
		try {
			this.keys = RSAKeys.fromKeyFiles(publicKeyPath, privateKeyPath);
		} catch (IOException | NoSuchAlgorithmException | InvalidKeySpecException e) {
			throw new RuntimeException(e);
		}
		return this;
	}

	/**
	 * Note: the JwtGenerator is fully configured as part of {@link #before()}
	 * method.
	 *
	 * @return the preconfigured Jwt token generator
	 */
	public JwtGenerator getPreconfiguredJwtGenerator() {
		JwtGenerator jwtGenerator = JwtGenerator.getInstance(service, clientId).withPrivateKey(keys.getPrivate());
		switch (service) {
		case XSUAA:
			jwtGenerator.withHeaderParameter(TokenHeader.JWKS_URL, jwksUrl).withClaimValue(TokenClaims.XSUAA.GRANT_TYPE,
					OAuth2TokenServiceConstants.GRANT_TYPE_USER_TOKEN);
			break;
		default:
			jwtGenerator.withClaimValue(TokenClaims.ISSUER, wireMockServer.baseUrl());
			break;
		}

		return jwtGenerator;
	}

	/**
	 * Creates a very basic token on base of the preconfigured Jwt token generator.
	 * In case you like to specify further token claims, you can make use of
	 * {@link #getPreconfiguredJwtGenerator()}
	 *
	 * @return the token.
	 */
	public Token createToken() {
		return getPreconfiguredJwtGenerator().createToken();
	}

	/**
	 * Allows to stub further endpoints of the identity service. Returns null if the
	 * rule is not yet initialized as part of {@link #before()} method. You can find
	 * a detailed explanation on how to configure wire mock here:
	 * http://wiremock.org/docs/getting-started/
	 *
	 * @return an instance of WireMockRule
	 */
	@Nullable
	public WireMockServer getWireMockServer() {
		return wireMockServer;
	}

	/**
	 * Returns the URI of the embedded jetty server or null if not specified.
	 * 
	 * @return uri of the application server
	 */
	@Nullable
	public String getApplicationServerUri() {
		if (useApplicationServer) {
			return String.format(LOCALHOST_PATTERN, applicationServer.getURI().getPort());
		}
		return null;
	}

	/**
	 * Initializes the OAuthServer mock for WireMock and generates the JWK URL.
	 * Should be called before each test.
	 * 
	 * @throws IOException if the stub cannot be initialized
	 */
	public void setupStub() throws IOException {
		OAuth2ServiceEndpointsProvider endpointsProvider = new XsuaaDefaultEndpoints(
				String.format(LOCALHOST_PATTERN, wireMockPort));
		wireMockServer.stubFor(get(urlEqualTo(endpointsProvider.getJwksUri().getPath()))
				.willReturn(aResponse().withBody(createDefaultTokenKeyResponse())));
		wireMockServer.stubFor(get(urlEqualTo(DISCOVERY_ENDPOINT_DEFAULT))
				.willReturn(aResponse().withBody(createDefaultOidcConfigurationResponse())));
		jwksUrl = endpointsProvider.getJwksUri().toString();
	}

	/**
	 * Resets WireMock. Should be called after each test that the stubs are reseted to avoid unwanted side-effects. 
	 */
	public void resetStub() {
		wireMockServer.resetAll();
	}

	/**
	 * Shuts down Jetty and WireMock. Should be called when all tests are executed to avoid unwanted side-effects.
	 */
	public void tearDown() {
		wireMockServer.shutdown();
		try {
			if (useApplicationServer) {
				applicationServer.stop();
			}
		} catch (Exception e) {
			LOGGER.error("Failed to stop jetty server", e);
		}
	}
	
	private void setupWireMock() {
		if (wireMockPort == 0) {
			wireMockServer = new WireMockServer(options().dynamicPort());
		} else {
			wireMockServer = new WireMockServer(options().port(wireMockPort));
		}
		wireMockServer.start();
		wireMockPort = wireMockServer.port();
	}

	private void startApplicationServer() throws Exception {
		WebAppContext context = createWebAppContext();
		ServletHandler servletHandler = createServletHandler(context);

		applicationServletsByPath
				.forEach((path, servletHolder) -> servletHandler.addServletWithMapping(servletHolder, path));
		applicationServletFilters.forEach((filterHolder) -> servletHandler.addFilterWithMapping(filterHolder, "/*",
				EnumSet.of(DispatcherType.REQUEST)));

		servletHandler.addFilterWithMapping(new FilterHolder(new SecurityFilter()), "/*",
				EnumSet.of(DispatcherType.REQUEST));

		applicationServer = new Server(applicationServerOptions.getPort());
		applicationServer.setHandler(context);
		applicationServer.start();
	}

	private ServletHandler createServletHandler(WebAppContext context) {
		ConstraintSecurityHandler security = new ConstraintSecurityHandler();
		JettyTokenAuthenticator authenticator = new JettyTokenAuthenticator(
				applicationServerOptions.getTokenAuthenticator());
		security.setAuthenticator(authenticator);

		ServletHandler servletHandler = new ServletHandler();
		security.setHandler(servletHandler);
		context.setServletHandler(servletHandler);
		context.setSecurityHandler(security);

		return servletHandler;
	}

	private WebAppContext createWebAppContext() {
		WebAppContext context = new WebAppContext();
		context.setConfigurations(new Configuration[] { new AnnotationConfiguration(), new WebXmlConfiguration(),
				new WebInfConfiguration(), new PlusConfiguration(), new MetaInfConfiguration(),
				new FragmentConfiguration(), new EnvConfiguration() });
		context.setContextPath("/");
		context.setResourceBase("src/main/java/webapp");
		context.setParentLoaderPriority(true);
		return context;
	}

	private String createDefaultTokenKeyResponse() throws IOException {
		String encodedPublicKeyModulus = Base64.getUrlEncoder()
				.encodeToString(((RSAPublicKey) keys.getPublic()).getModulus().toByteArray());
		String encodedPublicKey = Base64.getEncoder().encodeToString(keys.getPublic().getEncoded());
		return IOUtils.resourceToString("/token_keys_template.json", StandardCharsets.UTF_8)
				.replace("$kid", "default-kid").replace("$public_key", encodedPublicKey)
				.replace("$modulus", encodedPublicKeyModulus);
	}

	private String createDefaultOidcConfigurationResponse() throws IOException {
		return IOUtils.resourceToString("/oidcConfigurationTemplate.json", StandardCharsets.UTF_8).replace("$issuer",
				wireMockServer.baseUrl());
	}
}
