package com.furkanfidanoglu.cruxaisummarize.animation;

import android.view.View;
import androidx.navigation.NavController;
import androidx.navigation.NavOptions;

import com.furkanfidanoglu.cruxaisummarize.R;
import com.google.android.material.bottomnavigation.BottomNavigationView;

public class NavigationAnimHelper {

    private static int currentTabIndex = 1;

    private static int getTabIndex(int menuItemId) {
        if (menuItemId == R.id.nav_history) return 0;
        if (menuItemId == R.id.nav_text) return 1;
        if (menuItemId == R.id.nav_profile) return 2;
        return 1;
    }

    public static void setupWithAnimations(BottomNavigationView bottomNav, NavController navController) {

        bottomNav.setOnItemSelectedListener(item -> {
            int newIndex = getTabIndex(item.getItemId());

            if (newIndex == currentTabIndex) {
                return true;
            }

            NavOptions.Builder navBuilder = new NavOptions.Builder()
                    .setLaunchSingleTop(true)
                    .setRestoreState(true)
                    .setPopUpTo(navController.getGraph().getStartDestinationId(), false, true);

            // Akıcı ve doğru yönlü animasyon mantığı
            if (newIndex > currentTabIndex) {
                // Sağa gidiş → yeni ekran sağdan gelir
                navBuilder.setEnterAnim(R.anim.slide_in_right)
                        .setExitAnim(R.anim.slide_out_left)
                        .setPopEnterAnim(R.anim.slide_in_left)
                        .setPopExitAnim(R.anim.slide_out_right);
            } else {
                // Sola gidiş → yeni ekran soldan gelir
                navBuilder.setEnterAnim(R.anim.slide_in_left)
                        .setExitAnim(R.anim.slide_out_right)
                        .setPopEnterAnim(R.anim.slide_in_right)
                        .setPopExitAnim(R.anim.slide_out_left);
            }

            navController.navigate(item.getItemId(), null, navBuilder.build());
            currentTabIndex = newIndex;

            return true;
        });

        navController.addOnDestinationChangedListener((controller, destination, arguments) -> {
            if (destination.getId() == R.id.plans) {
                bottomNav.setVisibility(View.GONE);
            } else {
                bottomNav.setVisibility(View.VISIBLE);

                if (bottomNav.getSelectedItemId() != destination.getId()) {
                    bottomNav.getMenu().findItem(destination.getId()).setChecked(true);
                    currentTabIndex = getTabIndex(destination.getId());
                }
            }
        });
    }
}