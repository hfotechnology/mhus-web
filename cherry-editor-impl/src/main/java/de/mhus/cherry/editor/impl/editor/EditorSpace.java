package de.mhus.cherry.editor.impl.editor;

import java.util.Date;
import java.util.LinkedList;
import java.util.List;

import org.vaadin.addon.borderlayout.BorderLayout;
import org.vaadin.sliderpanel.SliderPanel;
import org.vaadin.sliderpanel.SliderPanelBuilder;
import org.vaadin.sliderpanel.SliderPanelStyles;
import org.vaadin.sliderpanel.client.SliderMode;
import org.vaadin.sliderpanel.client.SliderPanelListener;
import org.vaadin.sliderpanel.client.SliderTabPosition;

import com.vaadin.server.Page;
import com.vaadin.server.ClientConnector;
import com.vaadin.server.ClientConnector.AttachEvent;
import com.vaadin.server.Page.BrowserWindowResizeEvent;
import com.vaadin.ui.Button;
import com.vaadin.ui.Button.ClickEvent;
import com.vaadin.ui.HorizontalLayout;
import com.vaadin.ui.Label;
import com.vaadin.ui.Notification;
import com.vaadin.ui.Panel;
import com.vaadin.ui.TabSheet;
import com.vaadin.ui.UI;
import com.vaadin.ui.VerticalLayout;

import de.mhus.cherry.portal.api.CherryApi;
import de.mhus.cherry.portal.api.VirtualHost;
import de.mhus.cherry.portal.api.WidgetApi;
import de.mhus.cherry.portal.api.control.EditorPanel;
import de.mhus.cherry.portal.api.control.EditorControl;
import de.mhus.cherry.portal.api.control.EditorControlFactory;
import de.mhus.cherry.portal.api.control.EditorFactory;
import de.mhus.cherry.portal.api.control.GuiLifecycle;
import de.mhus.cherry.portal.api.control.GuiUtil;
import de.mhus.cherry.portal.api.control.Navigable;
import de.mhus.cherry.portal.api.util.CherryUtil;
import de.mhus.lib.cao.CaoNode;
import de.mhus.lib.core.MString;
import de.mhus.lib.core.logging.Log;
import de.mhus.lib.core.security.Account;
import de.mhus.lib.core.util.Pair;
import de.mhus.lib.errors.MException;
import de.mhus.lib.karaf.MOsgi;
import de.mhus.lib.vaadin.VWorkBar;
import de.mhus.osgi.sop.api.Sop;
import de.mhus.osgi.sop.api.aaa.AccessApi;

public class EditorSpace extends VerticalLayout implements Navigable, GuiLifecycle {

	private static Log log = Log.getLog(EditorSpace.class);
	private static final long serialVersionUID = 1L;
	private Panel panel;
	private VerticalLayout contentLayout;
	private CaoNode resource;
	private EditorPanel editor;
	private Button bSave;
	private Button bCancel;
	private TabSheet tabs;
	private SliderPanel navigationSlider;
	private BorderLayout navigationContent;
	private BorderLayout createContent;
	private SliderPanel createSlider;
	private NavigationView navigation;
	private VWorkBar navigationToolBar;

	@Override
	public String navigateTo(String selection, String filter) {
		
		return doShow(filter);
		
	}

	@Override
	public void doInitialize() {
		
		panel = new Panel();
		setMargin(true);
		addComponent(panel);
		panel.setCaption("Editor");
		panel.setSizeFull();
		tabs = new TabSheet();
		
		HorizontalLayout sub = new HorizontalLayout();
		sub.setSizeFull();
		sub.addComponent(tabs);
		sub.setExpandRatio(tabs, 1);
		panel.setContent(sub);
		
		contentLayout = new VerticalLayout();
//		contentLayout.addComponent(l);

		navigationContent = new BorderLayout();
		navigationContent.setWidth( "100%" );
		navigationContent.setHeight("100%");
		
        navigationSlider =
                new SliderPanelBuilder(navigationContent, "Navigation")
	                .mode(SliderMode.RIGHT)
	                .tabPosition(SliderTabPosition.MIDDLE)
                    .flowInContent(true)
                    .autoCollapseSlider(true)
                    .zIndex(9980)
                    .animationDuration(200)
                    .style(SliderPanelStyles.COLOR_GRAY)
                    .listener(new SliderPanelListener() {
                        @Override
                        public void onToggle(final boolean expand) {
                       	 if (expand)
                    		 doResetNavigationContent();
                        }
                    }).build();
         sub.addComponent(navigationSlider);
 		navigationSlider.setFixedContentSize(800);
      
 		createContent = new BorderLayout();
 		createContent.setWidth("500px");
 		createContent.setHeight("100%");
 		
 		createSlider =
                 new SliderPanelBuilder(createContent, "Create")
 	                .mode(SliderMode.RIGHT)
 	                .tabPosition(SliderTabPosition.BEGINNING)
                     .flowInContent(true)
                     .autoCollapseSlider(true)
                     .zIndex(9980)
                     .animationDuration(500)
                     .style(SliderPanelStyles.COLOR_BLUE)
                     .listener(new SliderPanelListener() {
                         @Override
                         public void onToggle(final boolean expand) {
                        	 if (expand)
                        		 doResetCreateContent();
                         }
                     }).build();
          sub.addComponent(createSlider);
         
        this.addAttachListener(new ClientConnector.AttachListener() {
			
			@Override
			public void attach(AttachEvent event) {
				navigationContent.setWidth( "100%" );
				navigationSlider.setFixedContentSize(Page.getCurrent().getBrowserWindowWidth() - 200);
			}
		});
		Page.getCurrent().addBrowserWindowResizeListener(new Page.BrowserWindowResizeListener() {
			
			private static final long serialVersionUID = 1L;

			@Override
			public void browserWindowResized(BrowserWindowResizeEvent event) {
				navigationContent.setWidth( "100%" );
				navigationSlider.setFixedContentSize(event.getWidth() - 200);
			}
		});
		
	}

	protected void doResetCreateContent() {
		createContent.addComponent(new Label("Create"));
	}

	protected void doResetNavigationContent() {
		if (navigation == null) {
			navigation = new NavigationView();
			navigationContent.addComponent(navigation, BorderLayout.Constraint.CENTER);
			navigationContent.setWidth( "100%" );
			navigationSlider.setFixedContentSize(Page.getCurrent().getBrowserWindowWidth() - 200);
			
			navigationToolBar = new VWorkBar() {

				@Override
				public List<Pair<String, String>> getAddOptions() {
					LinkedList<Pair<String,String>> list = new LinkedList<>();
					list.add(new Pair<String, String>("Navigation", "...ddd.nav"));
					list.add(new Pair<String, String>("Page", "ddd.page"));
					return list;
				}

				@Override
				public List<Pair<String, String>> getModifyOptions() {
					LinkedList<Pair<String,String>> list = new LinkedList<>();
					list.add(new Pair<String, String>("Navigation", "...ddd.nav"));
					list.add(new Pair<String, String>("Page", "ddd.page"));
					return list;
				}

				@Override
				public List<Pair<String, String>> getDeleteOptions() {
					LinkedList<Pair<String,String>> list = new LinkedList<>();
					list.add(new Pair<String, String>("Navigation", "...ddd.nav"));
					list.add(new Pair<String, String>("Page", "ddd.page"));
					return list;
				}

				@Override
				protected void doModify(String action) {
					System.out.println("Modify: " + action);
				}

				@Override
				protected void doDelete(String action) {
					System.out.println("Delete: " + action);
				}

				@Override
				protected void doAdd(String action) {
					System.out.println("Add: " + action);
				}

			};
			navigationContent.addComponent(navigationToolBar, BorderLayout.Constraint.SOUTH);

		}
		
	}

	private synchronized String doShow(String resId) {
		log.d("show resource", resId);
		
		tabs.removeAllComponents();
		tabs.addTab(contentLayout, "Content");
		
		contentLayout.removeAllComponents();
		
		VirtualHost vHost = Sop.getApi(CherryApi.class).findVirtualHost( GuiUtil.getApi().getHost() );
		resource = vHost.getResourceResolver().getResource(vHost, resId);
		if (resource == null) {
			// resource not found
			return null;
		}
		
		EditorFactory factory = Sop.getApi(WidgetApi.class).getControlEditorFactory(vHost,resource);
		if (factory == null) {
			// editor not found
			return null;
		}
		
		doFillTabs(resource, factory);
		
		
		Panel editorPanel = new Panel();
		editorPanel.setSizeFull();
		contentLayout.addComponent(editorPanel);

		try {
			editor = factory.createEditor(resource.getWritableNode());
			editor.setSizeFull();
			editor.setMargin(true);
			editorPanel.setContent(editor);
			editor.initUi();
			panel.setCaption( editor.getTitle() );
		} catch (MException e) {
			log.e(e);
			return null;
		}
		
		HorizontalLayout buttonPanel = new HorizontalLayout();
		bCancel = new Button("Cancel", new Button.ClickListener() {
			
			@Override
			public void buttonClick(ClickEvent event) {
				doCancel();
			}
		});
		buttonPanel.addComponent(bCancel);
		Label dummy = new Label(" ");
		buttonPanel.addComponent(dummy);
		buttonPanel.setExpandRatio(dummy, 1f);
		bSave = new Button("Save", new Button.ClickListener() {
			
			@Override
			public void buttonClick(ClickEvent event) {
				doSave();
			}
		});
		buttonPanel.addComponent(bSave);
		buttonPanel.setSizeFull();
		contentLayout.addComponent(buttonPanel);
		contentLayout.setExpandRatio(editorPanel, 1f);
		contentLayout.setMargin(true);
		contentLayout.setSpacing(true);
		//contentLayout.setSizeFull();		
		
		return "Edit " + resource.getString("title", resource.getName());
	}

	private void doFillTabs(CaoNode res, EditorFactory editorFactory) {
		AccessApi aaa = Sop.getApi(AccessApi.class);
		Account account = aaa.getCurrentOrGuest().getAccount();
		for (EditorControlFactory factory : CherryUtil.orderServices(EditorSpace.class, EditorControlFactory.class)) {
			if (aaa.hasGroupAccess(account, EditorSpace.class, factory.getName(), "create")) {
				EditorControl c = factory.createEditorControl(res, editorFactory);
				if (c != null) {
					tabs.addTab(c, factory.getName());
				}
			}
		}
		
		
	}

	protected void doCancel() {
		doBack();
	}

	protected void doSave() {
		String error = editor.doSave();
		if (error != null) {
			UI.getCurrent().showNotification(error);
			return;
		}
		doBack();
	}

	private void doBack() {
		GuiUtil.getApi().navigateBack();
	}

	@Override
	public void doDestroy() {
		// TODO Auto-generated method stub
		
	}	

}
