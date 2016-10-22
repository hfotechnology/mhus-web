package de.mhus.cherry.portal.impl;

import java.io.IOException;

import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import aQute.bnd.annotation.component.Component;
import de.mhus.cherry.portal.api.CherryUtil;
import de.mhus.cherry.portal.api.NavigationProvider;
import de.mhus.cherry.portal.api.VirtualHost;
import de.mhus.lib.cao.CaoNode;

@Component(provide = Servlet.class, properties = "alias=/*", name="CherryServlet",servicefactory=true)
public class CherryServlet extends HttpServlet {

	private static final long serialVersionUID = 1L;
	
	@Override
	protected void service(HttpServletRequest req, HttpServletResponse res)
			throws ServletException, IOException {
			
		String host = req.getHeader("Host");
		VirtualHost vHost = CherryUtil.findVirtualHost(host);
		NavigationProvider navProvider = vHost.getNavigationProvider();
		
		if (navProvider == null) {
			res.sendError(HttpServletResponse.SC_BAD_GATEWAY);
			return;
		}
		
		CherryCallContext callContext = new CherryCallContext();
		callContext.setHttpRequest(req);
		callContext.setHttpResponse(res);
		String path = callContext.getHttpPath();
		
		CaoNode navResource = navProvider.getNode(path);
		
		if (navResource == null) {
			vHost.sendError(callContext, HttpServletResponse.SC_NOT_FOUND);
			return;
		}
		callContext.setNavigationResource(navResource);
		callContext.setVirtualHost(vHost);
		callContext.setHttpServlet(this);
		vHost.processRequest( callContext );
		
	}
	
}
