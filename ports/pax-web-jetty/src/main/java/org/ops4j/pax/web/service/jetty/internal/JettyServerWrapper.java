/* Copyright 2007 Alin Dreghiciu.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.
 *
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.ops4j.pax.web.service.jetty.internal;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.Collections;
import java.util.Comparator;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.IdentityHashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import javax.servlet.ServletException;

import org.eclipse.jetty.security.Authenticator;
import org.eclipse.jetty.security.SecurityHandler;
import org.eclipse.jetty.security.authentication.BasicAuthenticator;
import org.eclipse.jetty.security.authentication.ClientCertAuthenticator;
import org.eclipse.jetty.security.authentication.DigestAuthenticator;
import org.eclipse.jetty.security.authentication.FormAuthenticator;
import org.eclipse.jetty.security.authentication.SpnegoAuthenticator;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HandlerContainer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.SessionIdManager;
import org.eclipse.jetty.server.SessionManager;
import org.eclipse.jetty.server.handler.ContextHandler.Context;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.server.session.AbstractSessionIdManager;
import org.eclipse.jetty.server.session.AbstractSessionManager;
import org.eclipse.jetty.server.session.HashSessionIdManager;
import org.eclipse.jetty.server.session.HashSessionManager;
import org.eclipse.jetty.server.session.SessionHandler;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.security.Constraint;
import org.ops4j.pax.swissbox.core.BundleUtils;
import org.ops4j.pax.web.service.WebContainerConstants;
import org.ops4j.pax.web.service.spi.model.ContextModel;
import org.ops4j.pax.web.service.spi.model.Model;
import org.ops4j.pax.web.service.spi.model.ServerModel;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.service.http.HttpContext;
import org.osgi.util.tracker.ServiceTracker;
import org.osgi.util.tracker.ServiceTrackerCustomizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.mhus.osgi.cherry.api.central.CentralCallContext;
import de.mhus.osgi.cherry.api.central.CentralRequestHandler;
import de.mhus.osgi.cherry.api.central.CentralRequestHandlerAdmin;
import de.mhus.osgi.cherry.api.central.ConfigurableHandler;

/**
 * Jetty server with a handler collection specific to Pax Web.
 */
class JettyServerWrapper extends Server implements CentralRequestHandlerAdmin {

	private static final Logger LOG = LoggerFactory
			.getLogger(JettyServerWrapper.class);

	private LinkedList<CentralRequestHandler> centralHandlers = null;
	private Properties centralHandlerRules = null;
	static JettyServerWrapper instance = null;
	private ServiceTracker<CentralRequestHandler, CentralRequestHandler> tracker;
	
	private static final class ServletContextInfo {

		private final HttpServiceContext handler;
		private final AtomicInteger refCount = new AtomicInteger(1);

		public ServletContextInfo(HttpServiceContext handler) {
			super();
			this.handler = handler;
		}

		public int incrementRefCount() {
            return refCount.incrementAndGet();
		}

		public int decrementRefCount() {
			return refCount.decrementAndGet();
		}

		public HttpServiceContext getHandler() {
			return handler;
		}
	}

	private final ServerModel serverModel;
	private final Map<HttpContext, ServletContextInfo> contexts = new IdentityHashMap<HttpContext, ServletContextInfo>();
	private Map<String, Object> contextAttributes;
	private Integer sessionTimeout;
	private String sessionCookie;
	private String sessionDomain;
	private String sessionPath;
	private String sessionUrl;
	private String sessionWorkerName;
	private Boolean lazyLoad;
	private String storeDirectory;

	private File serverConfigDir;

	private URL serverConfigURL;

	private Boolean sessionCookieHttpOnly;

	private final ReadWriteLock rwLock = new ReentrantReadWriteLock();
	private final Lock readLock = rwLock.readLock();
	private final Lock writeLock = rwLock.writeLock();

	private BundleContext bc;

	JettyServerWrapper(ServerModel serverModel) {
		this.serverModel = serverModel;
		setHandler(new JettyServerHandlerCollection(serverModel));
		// setHandler( new HandlerCollection(true) );
		instance = this;
		
		bc = FrameworkUtil.getBundle(getClass()).getBundleContext();
		tracker = new ServiceTracker<>(bc, CentralRequestHandler.class, new WSCustomizer());
		tracker.open();
	}

	public void configureContext(final Map<String, Object> attributes,
			final Integer sessionTimeout, final String sessionCookie,
			final String sessionUrl, final Boolean sessionCookieHttpOnly,
			final String sessionWorkerName, final Boolean lazyLoad,
			final String storeDirectory) {
		this.contextAttributes = attributes;
		this.sessionTimeout = sessionTimeout;
		this.sessionCookie = sessionCookie;
		this.sessionUrl = sessionUrl;
		this.sessionCookieHttpOnly = sessionCookieHttpOnly;
		this.sessionWorkerName = sessionWorkerName;
		this.lazyLoad = lazyLoad;
		this.storeDirectory = storeDirectory;
	}

	public boolean doHandleBeforeRequest(CentralCallContext context)
			throws IOException, ServletException {
		
		synchronized (this) {
	
			checkCentralHandlers();
			for (int i = 0; i < centralHandlers.size(); i++) {
				CentralRequestHandler service = centralHandlers.get(i);
				try {
					context.setLastHandler(i);
					if (service.isEnabled() && service.doHandleBefore(context)) return true;
				} catch (Throwable e) {
					e.printStackTrace();
				}
			}
		}
		return false;
	}
	
	public boolean doHandleAfterRequest(CentralCallContext context)
			throws IOException, ServletException {
		
		synchronized (this) {
			
			checkCentralHandlers();
			int start = Math.min(context.getLastHandler(), centralHandlers.size()-1);
			for (int i = start; i >= 0; i--) {
				CentralRequestHandler service = centralHandlers.get(i);
				try {
					if (service.isEnabled())
						service.doHandleAfter(context);
				} catch (Throwable e) {
					e.printStackTrace();
				}
			}
		}
		return false;
	}

	private void checkCentralHandlers() {
		if (centralHandlers == null)
			updateCentralHandlers(null);
	}

	public void updateCentralHandlers(Properties rules) {
		
		LOG.info("update");
		
		if (rules != null)
			centralHandlerRules = rules;
		else
			rules = centralHandlerRules;
		
		synchronized (this) {
	
			BundleContext ctx = FrameworkUtil.getBundle(getClass()).getBundleContext();
			LinkedList<CentralRequestHandler> newList = new LinkedList<CentralRequestHandler>();
			try {
				for ( ServiceReference<CentralRequestHandler> ref : ctx.getServiceReferences(CentralRequestHandler.class, null)) {
					CentralRequestHandler service = ctx.getService(ref);
					if (service != null) {// TODO rules ignore property
						if (service instanceof ConfigurableHandler)
							((ConfigurableHandler)service).configure(rules);
						newList.add(service);
					}
				}
				
				if (rules != null) {
					String[] loadClasses = rules.getProperty("load","").split(",");
					for (String loadClass : loadClasses) {
						try {
							CentralRequestHandler inst = (CentralRequestHandler)Class.forName(loadClass).newInstance();
							if (inst instanceof ConfigurableHandler)
								((ConfigurableHandler)inst).configure(rules);
							newList.add(inst);
						} catch (InstantiationException | IllegalAccessException
								| ClassNotFoundException e) {
							e.printStackTrace();
						}
					}
				}
				
				Collections.sort(newList, new Comparator<CentralRequestHandler>() {
	
					@Override
					public int compare(CentralRequestHandler o1,
							CentralRequestHandler o2) {
						return Double.compare(o1.getSortHint(), o2.getSortHint());
					}
					
				});
				
				centralHandlers = newList;
				
			} catch (InvalidSyntaxException e) {
				e.printStackTrace();
			}
		}
	}
	
	
	HttpServiceContext getContext(final HttpContext httpContext) {
		readLock.lock();
		try {
			ServletContextInfo servletContextInfo = contexts.get(httpContext);
			if (servletContextInfo != null) {
				return servletContextInfo.getHandler();
			}
			return null;
		} finally {
			readLock.unlock();
		}
	}

	HttpServiceContext getOrCreateContext(final Model model) {
		return getOrCreateContext(model.getContextModel());
	}

	HttpServiceContext getOrCreateContext(final ContextModel model) {
		final HttpContext httpContext = model.getHttpContext();
		ServletContextInfo context = null;
		try {
			readLock.lock();
			if (contexts.containsKey(httpContext)) {
				context = contexts.get(httpContext);
			} else {
				try {
					readLock.unlock();
					writeLock.lock();
					if (!contexts.containsKey(httpContext)) {
						LOG.debug(
								"Creating new ServletContextHandler for HTTP context [{}] and model [{}]",
								httpContext, model);

						context = new ServletContextInfo(this.addContext(model));
						contexts.put(httpContext, context);
					} else {
						context = contexts.get(httpContext);
					}
				} finally {
					readLock.lock();
					writeLock.unlock();
				}
			}
		} finally {
			readLock.unlock();
		}
		return context.getHandler();
	}

	void removeContext(final HttpContext httpContext) {
        ServletContextInfo context;
        try {
            readLock.lock();
            context = contexts.get(httpContext);
            if (context == null) {
                return;
            }
            int nref = context.decrementRefCount();
            if (nref <= 0) {
                try {
                    readLock.unlock();
                    writeLock.lock();
                    LOG.debug("Removing ServletContextHandler for HTTP context [{}].",
                            httpContext);
                    context = contexts.remove(httpContext);
                } finally {
                    readLock.lock();
                    writeLock.unlock();
                }
            } else {
                LOG.debug(
                        "ServletContextHandler for HTTP context [{}] referenced [{}] times.",
                        httpContext, nref);
                return;
            }
        } finally {
            readLock.unlock();
        }
        // Destroy the context outside of the locking region
        if (context != null) {
			HttpServiceContext sch = context.getHandler();
            sch.unregisterService();
            try {
                sch.stop();
            } catch (Throwable t) { // CHECKSTYLE:SKIP
                // Ignore
            }
            sch.getServletHandler().setServer(null);
            sch.getSecurityHandler().setServer(null);
            sch.getSessionHandler().setServer(null);
            sch.getErrorHandler().setServer(null);
            ((HandlerCollection) getHandler()).removeHandler(sch);
            sch.destroy();
		}
	}

	private HttpServiceContext addContext(final ContextModel model) {
		Bundle bundle = model.getBundle();
		BundleContext bundleContext = BundleUtils.getBundleContext(bundle);
		HttpServiceContext context = new HttpServiceContext(
				(HandlerContainer) getHandler(), model.getContextParams(),
				getContextAttributes(bundleContext), model.getContextName(),
				model.getHttpContext(), model.getAccessControllerContext(),
				model.getContainerInitializers(), model.getJettyWebXmlURL(),
				model.getVirtualHosts(), model.getConnectors());
		context.setClassLoader(model.getClassLoader());
		Integer modelSessionTimeout = model.getSessionTimeout();
		if (modelSessionTimeout == null) {
			modelSessionTimeout = sessionTimeout;
		}
		String modelSessionCookie = model.getSessionCookie();
		if (modelSessionCookie == null) {
			modelSessionCookie = sessionCookie;
		}
		String modelSessionDomain = model.getSessionDomain();
		if (modelSessionDomain == null) {
			modelSessionDomain = sessionDomain;
		}
		String modelSessionPath = model.getSessionPath();
		if (modelSessionPath == null) {
			modelSessionPath = sessionPath;
		}
		String modelSessionUrl = model.getSessionUrl();
		if (modelSessionUrl == null) {
			modelSessionUrl = sessionUrl;
		}
		Boolean modelSessionCookieHttpOnly = model.getSessionCookieHttpOnly();
		if (modelSessionCookieHttpOnly == null) {
			modelSessionCookieHttpOnly = sessionCookieHttpOnly;
		}
		String workerName = model.getSessionWorkerName();
		if (workerName == null) {
			workerName = sessionWorkerName;
		}
		configureSessionManager(context, modelSessionTimeout,
				modelSessionCookie, modelSessionDomain, modelSessionPath, modelSessionUrl,
				modelSessionCookieHttpOnly, workerName, lazyLoad,
				storeDirectory);

		if (model.getRealmName() != null && model.getAuthMethod() != null) {
			configureSecurity(context, model.getRealmName(),
					model.getAuthMethod(), model.getFormLoginPage(),
					model.getFormErrorPage());
		}

		LOG.debug("Added servlet context: " + context);

		if (isStarted()) {
			try {
				LOG.debug("(Re)starting servlet contexts...");
				// start the server handler if not already started
				Handler serverHandler = getHandler();
				if (!serverHandler.isStarted() && !serverHandler.isStarting()) {
					serverHandler.start();
				}
				// if the server handler is a handler collection, seems like
				// jetty will not automatically
				// start inner handlers. So, force the start of the created
				// context
				if (!context.isStarted() && !context.isStarting()) {
					LOG.debug("Registering ServletContext as service. ");
					Dictionary<String, String> properties = new Hashtable<String, String>();
					properties.put("osgi.web.symbolicname",
							bundle.getSymbolicName());

					Dictionary<?, ?> headers = bundle.getHeaders();
					String version = (String) headers
							.get(Constants.BUNDLE_VERSION);
					if (version != null && version.length() > 0) {
						properties.put("osgi.web.version", version);
					}

					Context servletContext = context.getServletContext();
					String webContextPath = context.getContextPath();

					properties.put("osgi.web.contextpath", webContextPath);

					context.registerService(bundleContext, properties);
					LOG.debug("ServletContext registered as service. ");

				}
			} catch (Exception ignore) { // CHECKSTYLE:SKIP
				LOG.error(
						"Could not start the servlet context for http context ["
								+ model.getHttpContext() + "]", ignore);
			}
		}
		return context;
	}

	/**
	 * Sets the security authentication method and the realm name on the
	 * security handler. This has to be done before the context is started.
	 * 
	 * @param context
	 * @param realmName
	 * @param authMethod
	 * @param formLoginPage
	 * @param formErrorPage
	 */
	private void configureSecurity(ServletContextHandler context,
			String realmName, String authMethod, String formLoginPage,
			String formErrorPage) {
		final SecurityHandler securityHandler = context.getSecurityHandler();

		Authenticator authenticator = null;
		// TODO: switching to JDK7 this will be a switch
		if (Constraint.__FORM_AUTH.equals(authMethod)) {
			authenticator = new FormAuthenticator();
			securityHandler.setInitParameter(
					FormAuthenticator.__FORM_LOGIN_PAGE, formLoginPage);
			securityHandler.setInitParameter(
					FormAuthenticator.__FORM_ERROR_PAGE, formErrorPage);
		} else if (Constraint.__BASIC_AUTH.equals(authMethod)) {
			authenticator = new BasicAuthenticator();
		} else if (Constraint.__DIGEST_AUTH.equals(authMethod)) {
			authenticator = new DigestAuthenticator();
		} else if (Constraint.__CERT_AUTH.equals(authMethod)) {
			authenticator = new ClientCertAuthenticator();
		} else if (Constraint.__CERT_AUTH2.equals(authMethod)) {
			authenticator = new ClientCertAuthenticator();
		} else if (Constraint.__SPNEGO_AUTH.equals(authMethod)) {
			authenticator = new SpnegoAuthenticator();
		} else {
			LOG.warn("UNKNOWN AUTH METHOD: " + authMethod);
		}

		securityHandler.setAuthenticator(authenticator);

		securityHandler.setRealmName(realmName);

	}

	/**
	 * Returns a list of servlet context attributes out of configured properties
	 * and attribues containing the bundle context associated with the bundle
	 * that created the model (web element).
	 * 
	 * @param bundleContext
	 *            bundle context to be set as attribute
	 * 
	 * @return context attributes map
	 */
	private Map<String, Object> getContextAttributes(
			final BundleContext bundleContext) {
		final Map<String, Object> attributes = new HashMap<String, Object>();
		if (contextAttributes != null) {
			attributes.putAll(contextAttributes);
		}
		attributes.put(WebContainerConstants.BUNDLE_CONTEXT_ATTRIBUTE,
				bundleContext);
		attributes
				.put("org.springframework.osgi.web.org.osgi.framework.BundleContext",
						bundleContext);
		return attributes;
	}

	/**
	 * Configures the session time out by extracting the session
	 * handlers->sessionManager for the context.
	 * 
	 * @param context
	 *            the context for which the session timeout should be configured
	 * @param minutes
	 *            timeout in minutes
	 * @param cookie
	 *            Session cookie name. Defaults to JSESSIONID. If set to null or
	 *            "none" no cookies will be used.
	 * @param domain
	 *            Session cookie domain. This defaults to the current hostname.
	 * @param path
	 *            Session cookie path. This defaults to the current servlet 
	 *            context path.
	 * @param url
	 *            session URL parameter name. Defaults to jsessionid. If set to
	 *            null or "none" no URL rewriting will be done.
	 * @param cookieHttpOnly
	 *            configures if the Cookie is valid for http only (not https)
	 * @param workerName
	 *            name appended to session id, used to assist session affinity
	 *            in a load balancer
	 */
	private void configureSessionManager(final ServletContextHandler context,
			final Integer minutes, final String cookie, final String domain, 
			final String path, final String url, final Boolean cookieHttpOnly, 
			final String workerName, final Boolean lazyLoad, 
			final String storeDirectory) {
		LOG.debug("configureSessionManager for context [" + context
				+ "] using - timeout:" + minutes + ", cookie:" + cookie
				+ ", url:" + url + ", cookieHttpOnly:" + cookieHttpOnly
				+ ", workerName:" + workerName + ", lazyLoad:" + lazyLoad
				+ ", storeDirectory: " + storeDirectory);

		final SessionHandler sessionHandler = context.getSessionHandler();
		if (sessionHandler != null) {
			final SessionManager sessionManager = sessionHandler
					.getSessionManager();
			if (sessionManager != null) {
				if (minutes != null) {
					sessionManager.setMaxInactiveInterval(minutes * 60);
					LOG.debug("Session timeout set to " + minutes
							+ " minutes for context [" + context + "]");
				}
				if (cookie == null || "none".equals(cookie)) {
					if (sessionManager instanceof AbstractSessionManager) {
						((AbstractSessionManager) sessionManager)
								.setUsingCookies(false);
						LOG.debug("Session cookies disabled for context ["
								+ context + "]");
					} else {
						LOG.debug("SessionManager isn't of type AbstractSessionManager therefore using cookies unchanged!");
					}
				} else {
					sessionManager.getSessionCookieConfig().setName(cookie);
					LOG.debug("Session cookie set to " + cookie
							+ " for context [" + context + "]");
					sessionManager.getSessionCookieConfig().setHttpOnly(cookieHttpOnly);
					LOG.debug("Session cookieHttpOnly set to "
							+ cookieHttpOnly + " for context [" + context
							+ "]");
				}
				if (domain != null && domain.length() > 0) {
					sessionManager.getSessionCookieConfig().setDomain(domain);
					LOG.debug("Session domain set to " + domain + " for context ["
							+ context + "]");
				}
				if (path != null && path.length() > 0) {
					sessionManager.getSessionCookieConfig().setPath(path);
					LOG.debug("Session path set to " + path + " for context ["
							+ context + "]");
				}
				if (url != null) {
					sessionManager.setSessionIdPathParameterName(url);
					LOG.debug("Session URL set to " + url + " for context ["
							+ context + "]");
				}
				if (workerName != null) {
					SessionIdManager sessionIdManager = sessionManager
							.getSessionIdManager();
					if (sessionIdManager == null) {
						sessionIdManager = new HashSessionIdManager();
						sessionManager.setSessionIdManager(sessionIdManager);
					}
					if (sessionIdManager instanceof AbstractSessionIdManager) {
						AbstractSessionIdManager s = (AbstractSessionIdManager) sessionIdManager;
						s.setWorkerName(workerName);
						LOG.debug("Worker name set to " + workerName
								+ " for context [" + context + "]");
					}
				}
				// PAXWEB-461
				if (lazyLoad != null) {
					LOG.debug("is LazyLoad active? {}", lazyLoad);
					if (sessionManager instanceof HashSessionManager) {
						((HashSessionManager) sessionManager)
								.setLazyLoad(lazyLoad);
					}
				}
				if (storeDirectory != null) {
					LOG.debug("storeDirectoy set to: {}", storeDirectory);
					if (sessionManager instanceof HashSessionManager) {
						File storeDir = null;
						try {
							storeDir = new File(storeDirectory);
							((HashSessionManager) sessionManager)
									.setStoreDirectory(storeDir);
						} catch (IOException e) { // CHECKSTYLE:SKIP
							// TODO Auto-generated catch block
							LOG.warn(
									"IOException while trying to set the StoreDirectory on the session Manager",
									e);
						}
					}
				}
			}
		}
	}

	/**
	 * @param serverConfigDir
	 *            the serverConfigDir to set
	 */
	public void setServerConfigDir(File serverConfigDir) {
		this.serverConfigDir = serverConfigDir;
	}

	/**
	 * @return the serverConfigDir
	 */
	public File getServerConfigDir() {
		return serverConfigDir;
	}

	public URL getServerConfigURL() {
		return serverConfigURL;
	}

	public void setServerConfigURL(URL serverConfigURL) {
		this.serverConfigURL = serverConfigURL;
	}

	@Override
	public CentralRequestHandler[] getCentralHandlers() {
		synchronized (this) {
			checkCentralHandlers();
			return centralHandlers.toArray(new CentralRequestHandler[centralHandlers.size()]);
		}
	}

	@Override
	public Properties getCentralHandlerProperties() {
		return centralHandlerRules; //TODO return a copy
	}

	@Override
	protected void doStop() throws Exception {
		tracker.close();
		super.doStop();
	}

	
	private class WSCustomizer implements ServiceTrackerCustomizer<CentralRequestHandler, CentralRequestHandler> {

		@Override
		public CentralRequestHandler addingService(
				ServiceReference<CentralRequestHandler> reference) {

			CentralRequestHandler service = bc.getService(reference);
			updateCentralHandlers(null);
			return service;
		}

		@Override
		public void modifiedService(
				ServiceReference<CentralRequestHandler> reference,
				CentralRequestHandler service) {
			updateCentralHandlers(null);
		}

		@Override
		public void removedService(
				ServiceReference<CentralRequestHandler> reference,
				CentralRequestHandler service) {
			updateCentralHandlers(null);
		}
		
	}
	
}
