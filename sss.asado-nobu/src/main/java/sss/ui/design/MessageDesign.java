package sss.ui.design;

import com.vaadin.annotations.AutoGenerated;
import com.vaadin.annotations.DesignRoot;
import com.vaadin.ui.Button;
import com.vaadin.ui.Label;
import com.vaadin.ui.TextArea;
import com.vaadin.ui.VerticalLayout;
import com.vaadin.ui.declarative.Design;

/** 
 * !! DO NOT EDIT THIS FILE !!
 * 
 * This class is generated by Vaadin Designer and will be overwritten.
 * 
 * Please make a subclass with logic and additional interfaces as needed,
 * e.g class LoginView extends LoginDesign implements View { }
 */
@DesignRoot
@AutoGenerated
@SuppressWarnings("serial")
public class MessageDesign extends VerticalLayout {
	protected Label fromLabel;
	protected Label deliveredAt;
	protected Button forwardMsgBtn;

	protected Button replyMsgBtn;
	protected Button deleteMsgBtn;
	protected TextArea messageText;

	public MessageDesign() {
		Design.read(this);
	}
}