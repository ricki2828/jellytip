package com.github.damontecres.wholphin.ui.cards

import com.github.damontecres.wholphin.jellytip.JellytipRowFocusScope
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.DiscoverItem
import com.github.damontecres.wholphin.data.model.Person
import com.github.damontecres.wholphin.ui.ifElse
import com.github.damontecres.wholphin.ui.rememberInt

@Composable
fun PersonRow(
    people: List<Person>,
    onClick: (Person) -> Unit,
    modifier: Modifier = Modifier,
    @StringRes title: Int = R.string.people_title,
    onLongClick: ((Int, Person) -> Unit)? = null,
) {
    val firstFocus = remember { FocusRequester() }
    var position by rememberInt()
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
    ) {
        ItemRowTitle(stringResource(title))
        JellytipRowFocusScope { rowFocusModifier ->
            LazyRow(
            state = rememberLazyListState(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(8.dp),
            modifier =
                rowFocusModifier
                    .fillMaxWidth()
                    .focusRestorer(firstFocus),
        ) {
            itemsIndexed(people) { index, person ->
                PersonCard(
                    person = person,
                    onClick = {
                        position = index
                        onClick.invoke(person)
                    },
                    onLongClick = {
                        position = index
                        onLongClick?.invoke(index, person)
                    },
                    modifier =
                        Modifier
                            .width(personRowCardWidth)
                            .ifElse(index == position, Modifier.focusRequester(firstFocus))
                            .animateItem(),
                )
            }
        }
        }
    }
}

@Composable
fun DiscoverPersonRow(
    people: List<DiscoverItem>,
    onClick: (DiscoverItem) -> Unit,
    modifier: Modifier = Modifier,
    @StringRes title: Int = R.string.people_title,
    onLongClick: ((Int, DiscoverItem) -> Unit)? = null,
) {
    val firstFocus = remember { FocusRequester() }
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
    ) {
        ItemRowTitle(stringResource(title))
        JellytipRowFocusScope { rowFocusModifier ->
            LazyRow(
            state = rememberLazyListState(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(8.dp),
            modifier =
                rowFocusModifier
                    .padding(start = 16.dp)
                    .fillMaxWidth()
                    .focusRestorer(firstFocus),
        ) {
            itemsIndexed(people) { index, person ->
                PersonCard(
                    name = person.title,
                    role = person.subtitle,
                    imageUrl = person.posterUrl,
                    favorite = false,
                    onClick = { onClick.invoke(person) },
                    onLongClick = { onLongClick?.invoke(index, person) },
                    modifier =
                        Modifier
                            .width(personRowCardWidth)
                            .ifElse(index == 0, Modifier.focusRequester(firstFocus))
                            .animateItem(),
                )
            }
        }
        }
    }
}

val personRowCardWidth = 108.dp
