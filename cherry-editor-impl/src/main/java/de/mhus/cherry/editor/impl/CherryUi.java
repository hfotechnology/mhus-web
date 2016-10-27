package de.mhus.cherry.editor.impl;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.TreeMap;

import javax.security.auth.Subject;

import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;
import org.osgi.util.tracker.ServiceTracker;
import org.osgi.util.tracker.ServiceTrackerCustomizer;

import com.vaadin.annotations.Theme;
import com.vaadin.annotations.Widgetset;
import com.vaadin.server.VaadinRequest;
import com.vaadin.ui.AbstractComponent;
import com.vaadin.ui.MenuBar;
import com.vaadin.ui.UI;
import com.vaadin.ui.VerticalLayout;
import com.vaadin.ui.themes.ValoTheme;

import de.mhus.cherry.portal.api.editor.GuiApi;
import de.mhus.cherry.portal.api.editor.GuiLifecycle;
import de.mhus.cherry.portal.api.editor.GuiSpaceService;
import de.mhus.lib.core.IProperties;
import de.mhus.lib.core.MFile;
import de.mhus.lib.core.MProperties;
import de.mhus.lib.core.logging.Log;
import de.mhus.lib.core.logging.MLogUtil;
import de.mhus.lib.core.security.AccessControl;
import de.mhus.lib.vaadin.VaadinAccessControl;

@Theme("cherrytheme")
@Widgetset("de.mhus.cherry.editor.theme.CherryWidgetset")
public class CherryUi extends UI implements GuiApi {

	private static Log log = Log.getLog(CherryUi.class);
	private MenuBar menuBar;
	private AccessControl accessControl;
	private Desktop desktop;
	private ServiceTracker<GuiSpaceService,GuiSpaceService> spaceTracker;
	private TreeMap<String,GuiSpaceService> spaceList = new TreeMap<String, GuiSpaceService>();
	private HashMap<String, AbstractComponent> spaceInstanceList = new HashMap<String, AbstractComponent>(); 
	private BundleContext context;
	private String trailConfig = null;

	@Override
	protected void init(VaadinRequest request) {
		VerticalLayout content = new VerticalLayout();
		setContent(content);
		content.setSizeFull();
        content.addStyleName("view-content");
        content.setMargin(true);
        content.setSpacing(true);

        accessControl = new VaadinAccessControl("karaf");

        if (!accessControl.isUserSignedIn()) {
            setContent(new LoginScreen(accessControl, new LoginScreen.LoginListener() {
                @Override
                public void loginSuccessful() {
                    showMainView();
                }
            }));
        } else {
            showMainView();
        }

        context = FrameworkUtil.getBundle(getClass()).getBundleContext();
		spaceTracker = new ServiceTracker<>(context, GuiSpaceService.class, new GuiSpaceServiceTrackerCustomizer() );
		spaceTracker.open();

	}

	private void showMainView() {
        addStyleName(ValoTheme.UI_WITH_MENU);
        desktop = new Desktop(this);
        setContent(desktop);
		synchronized (this) {
			desktop.refreshSpaceList(spaceList);
		}
	}

	@Override
	public void close() {
		synchronized (this) {
			spaceTracker.close();
			spaceList.clear();
			for (AbstractComponent v : spaceInstanceList.values())
				if (v instanceof GuiLifecycle) ((GuiLifecycle)v).doDestroy();
			spaceInstanceList.clear();
		}
		super.close();
	}

	private class GuiSpaceServiceTrackerCustomizer implements ServiceTrackerCustomizer<GuiSpaceService,GuiSpaceService> {

		@Override
		public GuiSpaceService addingService(
				ServiceReference<GuiSpaceService> reference) {
			synchronized (this) {
				GuiSpaceService service = context.getService(reference);
				spaceList.put(service.getName(),service);
				if (desktop != null) desktop.refreshSpaceList(spaceList);
				return service;
			}
		}

		@Override
		public void modifiedService(
				ServiceReference<GuiSpaceService> reference,
				GuiSpaceService service) {
			synchronized (this) {
				spaceList.remove(service.getName());
				AbstractComponent v = spaceInstanceList.remove(service.getName());
				if (v instanceof GuiLifecycle) ((GuiLifecycle)v).doDestroy();
				service = context.getService(reference);
				spaceList.put(service.getName(),service);
				if (desktop != null) desktop.refreshSpaceList(spaceList);
			}
		}

		@Override
		public void removedService(ServiceReference<GuiSpaceService> reference,
				GuiSpaceService service) {
			synchronized (this) {
				spaceList.remove(service.getName());
				AbstractComponent v = spaceInstanceList.remove(service.getName());
				if (v instanceof GuiLifecycle) ((GuiLifecycle)v).doDestroy();
				if (desktop != null) desktop.refreshSpaceList(spaceList);
			}
		}
	}

	public AbstractComponent getSpaceComponent(String name) {
		GuiSpaceService space = spaceList.get(name);
		if (space == null) return null;
		AbstractComponent instance = spaceInstanceList.get(name);
		if (instance == null) {
			instance = space.createSpace();
			if (instance == null) return null;
			if (instance instanceof GuiLifecycle) ((GuiLifecycle)instance).doInitialize();
			spaceInstanceList.put(name, instance);
		}
		return instance;
	}

	public BundleContext getContext() {
		return context;
	}
	
	public GuiSpaceService getSpace(String name) {
		return spaceList.get(name);
	}
	
	public AccessControl getAccessControl() {
		return accessControl;
	}

	public void removeSpaceComponent(String name) {
		AbstractComponent c = spaceInstanceList.remove(name);
		if (c != null && c instanceof GuiLifecycle) ((GuiLifecycle)c).doDestroy();
	}

	@Override
	public boolean hasAccess(String role) {
		if (role == null || accessControl == null || !accessControl.isUserSignedIn())
			return false;

		try {
			File file = new File( "aaa/groupmapping/" + MFile.normalize(role.trim()).toLowerCase() + ".txt" );
			if (!file.exists()) {
				log.w("file not found",file);
				return false;
			}
			List<String> lines = MFile.readLines(file, true);
			for (String line : lines) {
				if (line.startsWith("not:")) {
					line = line.substring(4);
					if (accessControl.isUserInRole(line)) return false;
				} else
				if (line.startsWith("notuser:")) {
					line = line.substring(8);
					if (accessControl.getPrincipalName().equals(line)) return false;
				} else
				if (line.startsWith("user:")) {
					line = line.substring(5);
					if (accessControl.getPrincipalName().equals(line)) return true;
				} else
				if (line.equals("*") || accessControl.isUserInRole(line)) return true;
			}
		} catch (Throwable t) {
			log.d(role,t);
		}
		return false;
		
	}

	@Override
	public IProperties getCurrentUserAccess() {
		MProperties accessRights = new MProperties();
		if (accessControl == null || !accessControl.isUserSignedIn())
			return accessRights;
		
		try {
			File file = new File("aaa/guiusers/" + MFile.normalize(accessControl.getPrincipalName().trim()).toLowerCase() + ".txt"); 
			if (!file.exists()) {
				log.w("file not found",file);
				return accessRights;
			}
			List<String> lines = MFile.readLines(file, true);
			if (lines == null)
				return new MProperties();
			
			if (lines.size() == 1 && "*".equals(lines.get(0)) || "admin".equalsIgnoreCase(lines.get(0))) {
				accessRights.setString("read", "*");
				accessRights.setString("write", "*");
			} else {
				accessRights = MProperties.load(file.getAbsolutePath());
			}
		} catch (Throwable t) {
			log.d(t);
		}
		
		return accessRights;
	}
	
	@Override
	public boolean openSpace(String spaceId, String subSpace, String search) {
		GuiSpaceService space = getSpace(spaceId);
		if (space == null) return false;
		desktop.showSpace(space, subSpace, search);
		
		return true;
	}

	@Override
	public Subject getCurrentUser() {
		return (Subject)getSession().getAttribute(VaadinAccessControl.SUBJECT_ATTR);
	}

	public void requestBegin() {
		if (trailConfig != null)
			MLogUtil.setTrailConfig(trailConfig);
		else
			MLogUtil.releaseTrailConfig();
	}

	public void requestEnd() {
		MLogUtil.releaseTrailConfig();
	}

	public String getTrailConfig() {
		return trailConfig;
	}

	public void setTrailConfig(String trailConfig) {
		if (trailConfig == null) {
			this.trailConfig = trailConfig;
			MLogUtil.releaseTrailConfig();
		} else {
			MLogUtil.setTrailConfig(trailConfig);
			this.trailConfig = MLogUtil.getTrailConfig();
		}
	}
	
}
