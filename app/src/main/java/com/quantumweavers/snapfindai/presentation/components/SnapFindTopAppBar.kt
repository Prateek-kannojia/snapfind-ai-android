package com.quantumweavers.snapfindai.presentation.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import com.quantumweavers.snapfindai.ui.theme.SnapFindAITheme

/**
 * The one app bar in the app. A screen supplies its title and its actions;
 * everything about how the bar *looks* is decided here.
 *
 * Two things it owns, and why they're together rather than in two helpers a
 * screen has to remember to call. **Colour**: Material's default paints the
 * bar `surface`, a different colour from the `background` the screens are
 * painted with, so a white strip sat above every screen and split a design
 * meant to read as one continuous surface. **Title weight**: bold, which
 * lived beside the results screen's bars, so the job grid's bar came out in
 * a different weight simply because nothing carried the rule to it.
 *
 * Both were fixed once as separate functions, and that was the bug in
 * miniature: a style you have to opt into twice is a style a third bar will
 * get half of. There is nothing to opt into now.
 *
 * Deliberately the title only. A button in [actions] keeps the weight its
 * variant gives it -- bolding everything would have the heading and the
 * thing-to-tap competing.
 *
 * The photo viewer is the one screen that doesn't use this: it is black
 * behind a full-screen photo, and builds its own bar saying exactly that.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnapFindTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        modifier = modifier,
        title = {
            Text(title, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
        },
        navigationIcon = navigationIcon,
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            // The same colour scrolled as at rest. Material raises a bar onto
            // `surfaceContainer` once content moves under it, which is the
            // tonal-elevation trick this theme turns off everywhere else --
            // letting it apply here would give the bar a different ground from
            // its own screen the moment the user scrolled.
            scrolledContainerColor = MaterialTheme.colorScheme.background,
        ),
    )
}

// On the colour it actually sits on, because the point of the container
// colour is that the bar should disappear into the screen behind it -- which
// a preview on white cannot show. A title alone, a title with an accented
// action, and a selection bar: enough to see that the heading and the action
// read as different things.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopAppBarPreviewContent() {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column {
            SnapFindTopAppBar(title = "SnapFind AI")
            SnapFindTopAppBar(
                title = "Your Matches",
                navigationIcon = {
                    IconButton(onClick = {}) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(
                        onClick = {},
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
                    ) {
                        Text("Save 17", fontWeight = FontWeight.Bold)
                    }
                },
            )
            SnapFindTopAppBar(
                title = "3 selected",
                navigationIcon = {
                    IconButton(onClick = {}) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel selection")
                    }
                },
                actions = {
                    TextButton(
                        onClick = {},
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
                    ) {
                        Text("Save 9", fontWeight = FontWeight.Bold)
                    }
                    IconButton(onClick = {}) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete selected",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                },
            )
        }
    }
}

@Preview(name = "App bars", showBackground = true, widthDp = 400)
@Composable
private fun SnapFindTopAppBarPreview() {
    SnapFindAITheme { TopAppBarPreviewContent() }
}

@Preview(
    name = "App bars - dark",
    showBackground = true,
    widthDp = 400,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun SnapFindTopAppBarDarkPreview() {
    SnapFindAITheme(darkTheme = true) { TopAppBarPreviewContent() }
}
