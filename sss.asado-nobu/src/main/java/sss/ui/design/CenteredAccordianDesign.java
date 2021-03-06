package sss.ui.design;

import com.vaadin.annotations.AutoGenerated;
import com.vaadin.annotations.DesignRoot;
import com.vaadin.ui.*;
import com.vaadin.ui.declarative.Design;

/**
 * Created by alan on 6/10/16.
 */
@DesignRoot
@AutoGenerated
@SuppressWarnings("serial")
public class CenteredAccordianDesign extends HorizontalLayout {

    protected CssLayout menuItems;
    protected Button unlockMnuBtn;
    protected Button claimMnuBtn;
    protected TextArea unlockInfoTextArea;
    protected TextArea claimInfoTextArea;
    protected ComboBox identityCombo;
    protected PasswordField unLockPhrase;
    protected PasswordField claimPhrase;
    protected TextField claimIdentityText;
    protected TextField claimTagText;
    protected TextField unlockTagText;
    protected Button claimBtn;
    protected Button unlockBtn;
    protected VerticalLayout rhsClaim;
    protected VerticalLayout rhsUnlock;

    public CenteredAccordianDesign() {
        Design.read(this);
    }
}

