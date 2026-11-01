package dev.improve.vrcode

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.improve.vrcode.pie.PieIconImage

const val QUICK_INPUTS_TITLE = "Quick inputs · Left hand"
const val QUICK_INPUTS_DETAIL = "Turn your left palm toward you and pinch your index finger and thumb. " +
  "Move toward a key and hold for a second, or keep going out along its spoke, to send it to the focused terminal."
const val QUICK_INPUTS_NOTE = "Choose a direction, then what it sends. Choosing a key that is already on the ring " +
  "swaps the two. Empty directions are skipped: each key's area reaches halfway to its neighbours."

/** Each keypad row, as the ring shows it around the pinch point (null is the pinch point itself). */
private val keypad = listOf(
  listOf(QuickInputSlot.TopLeft, QuickInputSlot.Top, QuickInputSlot.TopRight),
  listOf(QuickInputSlot.Left, null, QuickInputSlot.Right),
  listOf(QuickInputSlot.BottomLeft, QuickInputSlot.Bottom, QuickInputSlot.BottomRight),
)

/** Matches the ring's own item circles. */
private val RingItem = Color(0xE6202227)

/**
 * Settings → Customization → Quick inputs: the left index-pinch ring as a keypad of its eight
 * directions. Pick a direction, then the input it sends (or Leave empty). Changes apply at once.
 */
@Composable
fun QuickInputControls(settings: AppSettings, onChange: (AppSettings) -> Unit) {
  val ring = settings.quickInputs
  var selected by remember { mutableStateOf(ring.placed.firstOrNull()?.first ?: QuickInputSlot.TopRight) }
  fun put(input: QuickInput?) = onChange(settings.copy(quickInputs = ring.with(selected, input)))

  Text(QUICK_INPUTS_TITLE, style = MaterialTheme.typography.headlineSmall)
  Text(QUICK_INPUTS_DETAIL, color = MaterialTheme.colorScheme.onSurfaceVariant)
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.selectableGroup().testTag("quick-input-keypad"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      for (row in keypad) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          for (slot in row) {
            if (slot == null) PinchPoint() else SlotCell(slot, ring[slot], slot == selected) { selected = slot }
          }
        }
      }
    }
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      val input = ring[selected]
      Text(selected.label, style = MaterialTheme.typography.titleLarge)
      Text(
        if (input == null) "Empty: moving this way picks the nearest key instead." else "Sends ${input.label}: ${input.detail}.",
        Modifier.testTag("quick-input-selected-detail"),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      OutlinedButton({ put(null) }, Modifier.heightIn(min = 52.dp).testTag("quick-input-clear"), enabled = input != null) {
        Text("Leave empty")
      }
    }
  }
  Text("Put at ${selected.label.lowercase()}", style = MaterialTheme.typography.titleMedium)
  Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    for (row in QuickInput.entries.chunked(4)) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for (input in row) {
          InputOption(input, ring.slotOf(input), selected, Modifier.weight(1f)) { put(input) }
        }
        repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
      }
    }
  }
  Text(QUICK_INPUTS_NOTE, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
  TextButton({ onChange(settings.copy(quickInputs = QuickInputRing.Default)) }, Modifier.heightIn(min = 48.dp).testTag("quick-inputs-defaults")) {
    Text("Restore default quick inputs")
  }
}

@Composable
private fun SlotCell(slot: QuickInputSlot, input: QuickInput?, selected: Boolean, onSelect: () -> Unit) {
  Surface(
    Modifier.size(112.dp),
    shape = RoundedCornerShape(16.dp),
    color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
    border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
  ) {
    Column(
      Modifier.fillMaxSize().testTag("quick-input-slot-${slot.name}")
        .semantics(mergeDescendants = true) { contentDescription = "${slot.label}: ${input?.label ?: "Empty"}" }
        .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
        .padding(8.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
    ) {
      if (input == null) {
        Text("Empty", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.outline)
      } else {
        RingIcon(input, 44.dp)
        Spacer(Modifier.height(6.dp))
        Text(input.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
    }
  }
}

/** The keypad's centre: where the pinch opens the ring. */
@Composable
private fun PinchPoint() {
  Box(Modifier.size(112.dp), contentAlignment = Alignment.Center) {
    Box(
      Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
      contentAlignment = Alignment.Center,
    ) {
      Text("Pinch", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
  }
}

@Composable
private fun InputOption(input: QuickInput, at: QuickInputSlot?, selected: QuickInputSlot, modifier: Modifier, onSelect: () -> Unit) {
  val here = at == selected
  Surface(
    modifier,
    shape = RoundedCornerShape(14.dp),
    color = if (here) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
    border = BorderStroke(if (here) 2.dp else 1.dp, if (here) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
  ) {
    Row(
      Modifier.heightIn(min = 72.dp).testTag("quick-input-option-${input.storageValue}")
        .selectable(selected = here, role = Role.RadioButton, onClick = onSelect)
        .padding(horizontal = 10.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      RingIcon(input, 40.dp)
      Column(Modifier.padding(start = 10.dp).weight(1f)) {
        Text(input.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
          if (at == null || here) input.detail else "At ${at.label.lowercase()}",
          style = MaterialTheme.typography.bodySmall,
          color = if (at == null || here) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

/** [input]'s icon on the ring's dark circle, as the hand sees it. */
@Composable
private fun RingIcon(input: QuickInput, size: androidx.compose.ui.unit.Dp) {
  Box(Modifier.size(size).clip(CircleShape).background(RingItem), contentAlignment = Alignment.Center) {
    PieIconImage(input.icon, Modifier.size(size * 0.5f))
  }
}
