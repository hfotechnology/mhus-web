package de.mhus.cherry.editor.impl.pages;

import com.vaadin.ui.AbstractComponent;
import com.vaadin.ui.MenuBar.MenuItem;

import aQute.bnd.annotation.component.Component;
import de.mhus.cherry.portal.api.control.GuiSpaceService;
import de.mhus.lib.core.security.AccessControl;

@Component(immediate=true,provide=GuiSpaceService.class)
public class PagesSpaceService implements GuiSpaceService {

	@Override
	public String getName() {
		return "pages";
	}

	@Override
	public String getDisplayName() {
		return "Pages";
	}

	@Override
	public AbstractComponent createSpace() {
		return new PagesSpace();
	}

	@Override
	public boolean hasAccess(AccessControl control) {
		return true;
	}

	@Override
	public void createMenu(AbstractComponent space, MenuItem[] menu) {
		
	}

	@Override
	public boolean isHiddenSpace() {
		return false;
	}

	@Override
	public AbstractComponent createTile() {
		return null;
	}

}
