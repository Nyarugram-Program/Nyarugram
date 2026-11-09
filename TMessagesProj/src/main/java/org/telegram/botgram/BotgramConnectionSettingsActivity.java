package org.telegram.botgram;

import android.os.Build;
import android.view.View;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;
import java.util.function.Consumer;

/** Login and registration share this native, animated connection settings page. */
public final class BotgramConnectionSettingsActivity extends UniversalFragment {
    private boolean enabled;
    private final Consumer<Boolean> changed;

    public BotgramConnectionSettingsActivity(boolean enabled, Consumer<Boolean> changed) {
        this.enabled = enabled;
        this.changed = changed;
    }

    @Override protected CharSequence getTitle() {
        return LocaleController.getString(R.string.BotgramConnectionSettings);
    }

    @Override protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asSwitchNoIcon(1, LocaleController.getString(R.string.BotgramLargeClientHello))
                .setChecked(enabled));
    }

    @Override protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id != 1) return;
        if (Build.VERSION.SDK_INT < 29) {
            showError(this, R.string.BotgramLargeClientHelloUnsupported);
            return;
        }
        enabled = !enabled;
        changed.accept(enabled);
        listView.adapter.update(true);
    }

    public static void toggleAccount(BaseFragment fragment, int account, Runnable updated) {
        BotgramTransport transport = BotgramTransport.get(account);
        if (!transport.enabled()) return;
        if (Build.VERSION.SDK_INT < 29) {
            showError(fragment, R.string.BotgramLargeClientHelloUnsupported);
            return;
        }
        transport.setLargeClientHello(!transport.largeClientHello(), success -> {
            updated.run();
            if (!success) showError(fragment, R.string.ErrorOccurred);
        });
    }

    private static void showError(BaseFragment fragment, int message) {
        if (fragment.getParentActivity() == null) return;
        fragment.showDialog(new AlertDialog.Builder(fragment.getParentActivity(), fragment.getResourceProvider())
                .setTitle(LocaleController.getString(R.string.BotgramConnectionSettings))
                .setMessage(LocaleController.getString(message))
                .setPositiveButton(LocaleController.getString(R.string.OK), null).create());
    }

    @Override protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}
