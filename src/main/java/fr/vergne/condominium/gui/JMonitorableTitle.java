package fr.vergne.condominium.gui;

import java.awt.CardLayout;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;

import fr.vergne.condominium.core.monitorable.Monitorable;
import fr.vergne.condominium.core.repository.Repository;

@SuppressWarnings("serial")
public class JMonitorableTitle extends JPanel {
	private static final String MUTABLE = "mutable";
	private static final String IMMUTABLE = "immutable";
	private final CardLayout cardLayout;
	private String currentCard;
	private final JLabel immutableTitle;
	private final JTextField mutableTitle;

	public <I, M extends Monitorable<S>, S> JMonitorableTitle(Repository.Updatable<I, M> repository, I monitorableId,
			M monitorable) {
		cardLayout = new CardLayout();
		this.setLayout(cardLayout);

		String title = monitorable.title();
		immutableTitle = new JLabel(title);
		mutableTitle = new JTextField(title);

		this.add(immutableTitle, IMMUTABLE);
		this.add(mutableTitle, MUTABLE);

		showImmutable();

		mutableTitle.addKeyListener(new KeyAdapter() {
			@Override
			public void keyReleased(KeyEvent event) {
				// Update and switch back to immutable upon ENTER
				if (event.getKeyCode() == KeyEvent.VK_ENTER) {
					String oldTitle = monitorable.title();
					String newTitle = mutableTitle.getText();
					if (oldTitle.equals(newTitle)) {
						// Nothing has changed, just switch back
						showImmutable();
					} else {
						monitorable.setTitle(newTitle);
						repository.update(monitorableId, monitorable);
						immutableTitle.setText(newTitle);
						showImmutable();
					}
				}
				// Ignore and switch back to immutable upon ESCAPE
				else if (event.getKeyCode() == KeyEvent.VK_ESCAPE) {
					mutableTitle.setText(monitorable.title());
					showImmutable();
				}
			}
		});

		monitorable.observeTitle((oldTitle, newTitle) -> {
			if (currentCard == IMMUTABLE) {
				// Both should be in sync, so change both
				immutableTitle.setText(newTitle);
				mutableTitle.setText(newTitle);
			} else if (mutableTitle.getText().equals(immutableTitle.getText())) {
				// Both are still in sync, so change both
				immutableTitle.setText(newTitle);
				mutableTitle.setText(newTitle);
			} else {
				// Mutable already changed, keep the user changes
				immutableTitle.setText(newTitle);
			}
		});
	}

	public void edit() {
		showMutable();
		mutableTitle.grabFocus();
	}

	private void showImmutable() {
		currentCard = IMMUTABLE;
		cardLayout.show(this, currentCard);
	}

	private void showMutable() {
		currentCard = MUTABLE;
		cardLayout.show(this, currentCard);
	}
}
