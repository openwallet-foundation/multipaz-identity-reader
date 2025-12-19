package org.multipaz.identityreader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator


/**
 * Creates and remembers a [NavigationState] instance that manages navigation state for a
 * hierarchical navigation structure with top-level routes and their associated back stacks.
 * Considered as a replacement for the need to depend on the rememberNavBackStack which is requiring
 * the problematic in some upstream solutions org.jetbrains.kotlin.plugin.serialization plugin dependency.
 *
 * This composable initializes the state required to handle navigation between top-level destinations
 * (like a bottom navigation bar) and the individual back stacks for each of those destinations.
 *
 * @param startRoute The initial top-level route to be displayed when the navigation starts.
 * @param topLevelRoutes A set of all available top-level routes (keys) that form the primary
 *                       navigation structure. Each key in this set will have its own independent
 *                       back stack initialized.
 * @return A [NavigationState] object that holds the current top-level selection and the
 *         history (back stack) for every defined top-level route.
 */
@Composable
fun rememberNavigationState(
    startRoute: NavKey,
    topLevelRoutes: Set<NavKey>
): NavigationState {

    val topLevelRoute = remember(startRoute) {
        mutableStateOf(startRoute)
    }

    val backStacks = topLevelRoutes.associateWith { key ->
        remember { mutableStateListOf(key) }
    }

    return remember(startRoute, topLevelRoutes) {
        NavigationState(
            startRoute = startRoute,
            topLevelRoute = topLevelRoute,
            backStacks = backStacks
        )
    }
}

/**
 * A state object that can be hoisted to control and observe navigation actions.
 * It manages the current navigation hierarchy, including the top-level route and the back stack
 * for each top-level destination.
 *
 * This class should be created and remembered using the [rememberNavigationState] composable.
 *
 * @param startRoute The initial or home route of the navigation graph.
 * @param topLevelRoute A mutable state holding the currently selected top-level route. This is often
 *   one of the primary destinations in a bottom navigation bar or navigation rail.
 * @param backStacks A map where each key is a top-level [NavKey] and the value is a [SnapshotStateList]
 *   representing the back stack of destinations navigated to within that top-level route.
 */
class NavigationState(
    val startRoute: NavKey,
    topLevelRoute: MutableState<NavKey>,
    val backStacks: Map<NavKey, SnapshotStateList<NavKey>>
) {
    var topLevelRoute: NavKey by topLevelRoute
    val stacksInUse: List<NavKey>
        get() = if (topLevelRoute == startRoute) {
            listOf(startRoute)
        } else {
            listOf(startRoute, topLevelRoute)
        }
}

/**
 * Converts the current [NavigationState] into a list of [NavEntry] objects suitable for display.
 *
 * This composable function processes the back stacks associated with the navigation state.
 * It applies necessary decorators (specifically [rememberSaveableStateHolderNavEntryDecorator])
 * to manage the lifecycle and saved state of the navigation entries.
 *
 * The resulting list represents the linear sequence of entries that should currently be
 * rendered, flattening the relevant back stacks based on the current top-level route configuration.
 *
 * @param entryProvider A lambda that creates a [NavEntry] for a given [NavKey]. This is used to
 * instantiate the actual content for each key in the back stack.
 * @return A [SnapshotStateList] containing the decorated [NavEntry] objects currently in use.
 */
@Composable
fun NavigationState.toEntries(
    entryProvider: (NavKey) -> NavEntry<NavKey>
): SnapshotStateList<NavEntry<NavKey>> {

    val decoratedEntries = backStacks.mapValues { (_, stack) ->
        val decorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator<NavKey>(),
        )
        rememberDecoratedNavEntries(
            backStack = stack,
            entryDecorators = decorators,
            entryProvider = entryProvider
        )
    }

    return stacksInUse
        .flatMap { decoratedEntries[it] ?: emptyList() }
        .toMutableStateList()
}

/**
 * A helper class for navigating within the application, managing both top-level switching
 * and stack-based history.
 *
 * This class provides a high-level API for modifying the [NavigationState], abstracting away
 * the details of stack management. It supports:
 * - Switching between top-level routes.
 * - Pushing new screens onto the current top-level stack.
 * - Popping the back stack or returning to the start destination.
 *
 * @property state The underlying state object holding the route history and current selection.
 */
class Navigator(val state: NavigationState) {
    /**
     * Navigates to a specific [route].
     *
     * If the [route] is one of the top-level routes (a key in the `backStacks`),
     * this function switches the current top-level context to that route.
     * Otherwise, it pushes the [route] onto the back stack of the currently active top-level route.
     *
     * @param route The destination [NavKey] to navigate to.
     */
    fun navigate(route: NavKey) {
        if (route in state.backStacks.keys) {
            state.topLevelRoute = route
        } else {
            state.backStacks[state.topLevelRoute]?.add(route)
        }
    }

    /**
     * Handles the "back" navigation action.
     *
     * This function attempts to navigate back in the current back stack. The behavior depends on the
     * current state of the navigation stack:
     * 1. If the current back stack contains more than one entry, the top-most entry is removed,
     *    effectively navigating to the previous screen within the current top-level route.
     * 2. If the current back stack is at its root (size is 1) and the current top-level route is
     *    not the start route, the navigation switches back to the [state.startRoute].
     *
     * @throws IllegalStateException if the back stack for the current top-level route cannot be found.
     */
    fun goBack() {
        val currentStack = state.backStacks[state.topLevelRoute]
            ?: error("Stack for ${state.topLevelRoute} not found")

        if (currentStack.size > 1) {
            currentStack.removeAt(currentStack.lastIndex) // Don't use removeLast() here.
        } else if (state.topLevelRoute != state.startRoute) {
            state.topLevelRoute = state.startRoute
        }
    }

    /**
     * Pops the top destination off the back stack of the current navigation flow.
     *
     * This operation delegates to [goBack], attempting to remove the current screen from the stack.
     * If the current stack has only one entry (the root of that stack), it may navigate back to the
     * start route (home) if currently on a different top-level route.
     */
    fun popBackStack() {
        goBack()
    }
}
