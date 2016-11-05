package de.mhus.cherry.portal.demo;

import com.vaadin.ui.RichTextArea;
import com.vaadin.ui.TextField;

import de.mhus.cherry.portal.api.Editor;
import de.mhus.lib.cao.CaoWritableElement;
import de.mhus.lib.core.logging.Log;
import de.mhus.lib.errors.MException;

public class SimpleEditor extends Editor {

	private static Log log = Log.getLog(SimpleEditor.class);
	private static final long serialVersionUID = 1L;
	private TextField title;
	private RichTextArea text;

	public SimpleEditor(CaoWritableElement data) {
		super(data);
	}

	@Override
	public void initUi() {
		
		title = new TextField("Title");
		title.setValue(data.getString("title", ""));
		addComponent(title);
		
		text = new RichTextArea();
		text.setValue(data.getString("text", ""));
		addComponent(text);
	}
	
	@Override
	public String getTitle() {
		return "Simple " + data.getString("title", "");
	}

	@Override
	public String doSave() {
		data.setString("title", title.getValue());
		data.setString("text", text.getValue());
		try {
			data.getUpdateOperation().doExecute();
		} catch (MException e) {
			log.e(e);
			return e.toString();
		}
		return null;
	}

}
