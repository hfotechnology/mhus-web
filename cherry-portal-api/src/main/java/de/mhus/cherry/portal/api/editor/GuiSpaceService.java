package de.mhus.cherry.portal.api.editor;

import com.vaadin.ui.AbstractComponent;
import com.vaadin.ui.MenuBar.MenuItem;

import de.mhus.lib.core.security.AccessControl;

public interface GuiSpaceService {

	String getName();
	String getDisplayName();
	AbstractComponent createSpace();
	boolean hasAccess(AccessControl control);
	void createMenu(MenuItem menu);
	
}
