package com.github.damontecres.wholphin.services

import androidx.navigation3.runtime.NavBackStack
import com.github.damontecres.wholphin.ui.nav.Destination
import org.acra.ACRA
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages navigating between pages and manages the app's back stack
 */
@Singleton
class NavigationManager
    @Inject
    constructor() {
        var backStack: MutableList<Destination> = NavBackStack(Destination.Home())

        /**
         * Goes up each time the app is sent back to Home ([goToHome]): from the side menu, or on the way to another
         * side menu page. Home reads it when it opens: when it has changed since Home was last shown, Home starts at
         * its top row; when it hasn't (coming back from a title opened from one of its rows), Home keeps its place
         * (owner, 2026-11-11)
         */
        var homeVisit = 0
            private set

        init {
            NavTiming.navigated(backStack.lastOrNull())
        }

        /**
         * Go to the specified [com.github.damontecres.wholphin.ui.nav.Destination]
         */
        fun navigateTo(destination: Destination) {
            backStack.add(destination)
            log()
        }

        /**
         * Go to the specified [Destination], but reset the back stack to Home first
         */
        fun navigateToFromDrawer(destination: Destination) {
            goToHome()
            backStack.add(destination)
            log()
        }

        /**
         * Go to the previous page
         */
        fun goBack() {
            synchronized(this) {
                if (backStack.size > 1) {
                    backStack.removeLastOrNull()
                }
            }
            log()
        }

        /**
         * Go all the way back to the home page
         */
        fun goToHome() {
            homeVisit++
            while (backStack.size > 1) {
                backStack.removeLastOrNull()
            }
            if (backStack[0] !is Destination.Home) {
                backStack[0] = Destination.Home()
            }
            log()
        }

        /**
         * Go all the way back to the home page, and reload it from scratch
         */
        fun reloadHome() {
            goToHome()
            val id = (backStack[0] as Destination.Home).id + 1
            backStack[0] = Destination.Home(id)
            log()
        }

        /**
         * Replace the current (top-most) page with the specified [Destination], keeping the rest of the back stack
         */
        fun replaceTop(destination: Destination) {
            synchronized(this) {
                if (backStack.isEmpty()) {
                    backStack.add(destination)
                } else {
                    backStack[backStack.lastIndex] = destination
                }
            }
            log()
        }

        /**
         * Resets the backstack to the specified destination
         */
        fun replace(destination: Destination) {
            while (backStack.size > 1) {
                backStack.removeLastOrNull()
            }
            if (backStack.isEmpty()) {
                backStack.add(0, destination)
            } else {
                backStack[0] = destination
            }
            log()
        }

        private fun log() {
            val dest = backStack.lastOrNull().toString()
            NavTiming.navigated(backStack.lastOrNull())
            Timber.i("Current Destination: %s", dest)
            ACRA.errorReporter.putCustomData("destination", dest)
        }
    }
