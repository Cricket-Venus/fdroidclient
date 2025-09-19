package org.fdroid.fdroid.views.main;

import android.Manifest;
import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.badge.BadgeDrawable;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.transition.MaterialFadeThrough;

import org.fdroid.fdroid.AppUpdateStatusManager;
import org.fdroid.fdroid.FDroidApp;
import org.fdroid.fdroid.Preferences;
import org.fdroid.fdroid.R;
import org.fdroid.fdroid.Utils;
import org.fdroid.fdroid.nearby.SDCardScannerService;
import org.fdroid.fdroid.nearby.SwapService;
import org.fdroid.fdroid.nearby.TreeUriScannerIntentService;
import org.fdroid.fdroid.nearby.WifiStateChangeService;
import org.fdroid.fdroid.views.AppDetailsActivity;
import org.fdroid.fdroid.views.apps.AppListActivity;
import org.fdroid.fdroid.work.AppUpdateWorker;

/**
 * Main view shown to users upon starting F-Droid.
 * Enhanced with Material Design components and better navigation.
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    private RecyclerView pager;
    private MainViewAdapter adapter;
    private BottomNavigationView bottomNavigation;
    private BadgeDrawable updatesBadge;

    private final ActivityResultLauncher<String> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                // no-op
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        FDroidApp fdroidApp = (FDroidApp) getApplication();
        fdroidApp.setSecureWindow(this);

        fdroidApp.applyPureBlackBackgroundInDarkTheme(this);
        EdgeToEdge.enable(this);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getWindow().setNavigationBarContrastEnforced(false);
        }

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Apply system UI visibility for light/dark mode
        int nightModeFlags = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        if (nightModeFlags != Configuration.UI_MODE_NIGHT_YES) {
            View decorView = getWindow().getDecorView();
            decorView.setSystemUiVisibility(decorView.getSystemUiVisibility() | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }

        // Setup TabLayout
        TabLayout tabLayout = findViewById(R.id.tab_layout);
        setUpTabLayout(tabLayout);

        // Setup RecyclerView for screen navigation
        adapter = new MainViewAdapter(this);
        pager = findViewById(R.id.main_view_pager);
        pager.setHasFixedSize(true);
        pager.setLayoutManager(new NonScrollingHorizontalLayoutManager(this));
        pager.setAdapter(adapter);

        ViewCompat.setOnApplyWindowInsetsListener(pager, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(insets.left, insets.top, insets.right, 0);
            return WindowInsetsCompat.CONSUMED;
        });

        // Bottom Navigation
        bottomNavigation = findViewById(R.id.bottom_navigation);
        setSelectedMenuInNav(Preferences.get().getBottomNavigationViewName());
        bottomNavigation.setOnNavigationItemSelectedListener(item -> {
            pager.scrollToPosition(item.getOrder());
            handleNavigationItemClick(item.getItemId());
            return true;
        });

        // Setup badge for updates
        updatesBadge = bottomNavigation.getOrCreateBadge(R.id.updates);
        updatesBadge.setVisible(false);
        AppUpdateStatusManager.getInstance(this).getNumUpdatableApps().observe(this, this::refreshUpdatesBadge);

        // Handle intents for the activity
        Intent intent = getIntent();
        if (handleMainViewSelectIntent(intent)) {
            return;
        }
        handleSearchOrAppViewIntent(intent);
    }

    private void setUpTabLayout(TabLayout tabLayout) {
        tabLayout.addTab(tabLayout.newTab().setText("Latest"));
        tabLayout.addTab(tabLayout.newTab().setText("Categories"));
        tabLayout.addTab(tabLayout.newTab().setText("Nearby"));
        tabLayout.addTab(tabLayout.newTab().setText("Updates"));
        tabLayout.addTab(tabLayout.newTab().setText("Settings"));

        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                pager.scrollToPosition(tab.getPosition());
                handleNavigationItemClick(tab.getPosition());
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
            }
        });
    }

    private void handleNavigationItemClick(int itemId) {
        if (itemId == R.id.latest) {
            Preferences.get().setBottomNavigationViewName(EXTRA_VIEW_LATEST);
        } else if (itemId == R.id.categories) {
            Preferences.get().setBottomNavigationViewName(EXTRA_VIEW_CATEGORIES);
        } else if (itemId == R.id.nearby) {
            Preferences.get().setBottomNavigationViewName(EXTRA_VIEW_NEARBY);
        } else if (itemId == R.id.updates) {
            Preferences.get().setBottomNavigationViewName(EXTRA_VIEW_UPDATES);
        } else if (itemId == R.id.settings) {
            Preferences.get().setBottomNavigationViewName(EXTRA_VIEW_SETTINGS);
        }
    }

    private void setSelectedMenuInNav(String viewName) {
        if (EXTRA_VIEW_LATEST.equals(viewName)) {
            pager.scrollToPosition(0);
        } else if (EXTRA_VIEW_CATEGORIES.equals(viewName)) {
            pager.scrollToPosition(1);
        } else if (EXTRA_VIEW_NEARBY.equals(viewName)) {
            pager.scrollToPosition(2);
        } else if (EXTRA_VIEW_UPDATES.equals(viewName)) {
            pager.scrollToPosition(3);
        } else if (EXTRA_VIEW_SETTINGS.equals(viewName)) {
            pager.scrollToPosition(4);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        FDroidApp.checkStartTor(this, Preferences.get());
    }

    private boolean handleMainViewSelectIntent(Intent intent) {
        if (intent.hasExtra(EXTRA_VIEW_NEARBY)) {
            setSelectedMenuInNav(R.id.nearby);
            return true;
        } else if (intent.hasExtra(EXTRA_VIEW_UPDATES)) {
            setSelectedMenuInNav(R.id.updates);
            return true;
        } else if (intent.hasExtra(EXTRA_VIEW_SETTINGS)) {
            setSelectedMenuInNav(R.id.settings);
            return true;
        }
        return false;
    }

    private void handleSearchOrAppViewIntent(Intent intent) {
        if (Intent.ACTION_SEARCH.equals(intent.getAction())) {
            String query = intent.getStringExtra(SearchManager.QUERY);
            if (query != null) performSearch(query);
            return;
        }
        final Uri data = intent.getData();
        if (data != null) {
            String packageName = data.getQueryParameter("id");
            if (!TextUtils.isEmpty(packageName)) {
                Intent appDetailsIntent = new Intent(this, AppDetailsActivity.class);
                appDetailsIntent.putExtra(AppDetailsActivity.EXTRA_APPID, packageName);
                startActivity(appDetailsIntent);
                finish();
            }
        }
    }

    private void performSearch(@NonNull String query) {
        Intent searchIntent = new Intent(this, AppListActivity.class);
        searchIntent.putExtra(AppListActivity.EXTRA_SEARCH_TERMS, query);
        startActivity(searchIntent);
    }

    private void refreshUpdatesBadge(int canUpdateCount) {
        if (canUpdateCount <= 0) {
            updatesBadge.setVisible(false);
            updatesBadge.clearNumber();
        } else {
            updatesBadge.setNumber(canUpdateCount);
            updatesBadge.setVisible(true);
        }
    }

    private static class NonScrollingHorizontalLayoutManager extends LinearLayoutManager {
        NonScrollingHorizontalLayoutManager(Context context) {
            super(context, LinearLayoutManager.HORIZONTAL, false);
        }

        @Override
        public boolean canScrollHorizontally() {
            return false;
        }

        @Override
        public boolean canScrollVertically() {
            return false;
        }
    }
}
