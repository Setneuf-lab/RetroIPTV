package com.ale.cineiptv.ui

import android.app.Activity
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.ale.cineiptv.R
import com.ale.cineiptv.data.ParentalControl
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Diálogos del control parental (pedir, crear y gestionar el PIN). */
object PinDialogs {

    private fun pinField(activity: Activity): Pair<FrameLayout, EditText> {
        val edit = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(4))
            gravity = Gravity.CENTER
            textSize = 28f
            letterSpacing = 0.5f
            hint = "••••"
        }
        val pad = (24 * activity.resources.displayMetrics.density).toInt()
        val box = FrameLayout(activity).apply { setPadding(pad, pad / 2, pad, 0); addView(edit) }
        return box to edit
    }

    private fun AlertDialog.showWithKeyboard(edit: EditText) {
        window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        show()
        edit.requestFocus()
    }

    /** Pide el PIN; si es correcto ejecuta [onOk]. */
    fun ask(activity: Activity, onOk: () -> Unit) {
        val (box, edit) = pinField(activity)
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.pin_enter)
            .setView(box)
            .setPositiveButton(R.string.ok, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            val submit: () -> Unit = {
                if (ParentalControl.checkPin(edit.text.toString())) {
                    dialog.dismiss(); onOk()
                } else {
                    edit.text.clear()
                    edit.error = activity.getString(R.string.pin_wrong)
                }
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { submit() }
            edit.setOnEditorActionListener { _, _, _ -> submit(); true }
        }
        dialog.showWithKeyboard(edit)
    }

    /** Crea un PIN nuevo (se pide dos veces). */
    fun create(activity: Activity, onDone: () -> Unit) {
        val (box, edit) = pinField(activity)
        var first: String? = null
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.pin_new)
            .setView(box)
            .setPositiveButton(R.string.ok, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            val submit: () -> Unit = {
                val pin = edit.text.toString()
                when {
                    pin.length != 4 -> { edit.error = activity.getString(R.string.pin_4_digits) }
                    first == null -> {
                        first = pin
                        edit.text.clear()
                        dialog.setTitle(R.string.pin_repeat)
                    }
                    first == pin -> {
                        ParentalControl.setPin(pin)
                        dialog.dismiss()
                        Toast.makeText(activity, R.string.pin_saved, Toast.LENGTH_SHORT).show()
                        onDone()
                    }
                    else -> {
                        first = null
                        edit.text.clear()
                        dialog.setTitle(R.string.pin_new)
                        edit.error = activity.getString(R.string.pin_mismatch)
                    }
                }
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { submit() }
            edit.setOnEditorActionListener { _, _, _ -> submit(); true }
        }
        dialog.showWithKeyboard(edit)
    }

    /** Menú de ajustes del control parental (se entra con PIN, o creando uno). */
    fun openSettings(activity: Activity) {
        if (!ParentalControl.hasPin) {
            create(activity) { showSettingsMenu(activity) }
        } else {
            ask(activity) { showSettingsMenu(activity) }
        }
    }

    private fun showSettingsMenu(activity: Activity) {
        val locked = ParentalControl.lockedGroups.sorted()
        val items = arrayOf(
            activity.getString(
                if (ParentalControl.autoAdult) R.string.parental_auto_on else R.string.parental_auto_off
            ),
            activity.getString(R.string.parental_groups_fmt, locked.size),
            activity.getString(R.string.parental_change_pin),
            activity.getString(R.string.parental_disable),
        )
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.parental_title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        ParentalControl.autoAdult = !ParentalControl.autoAdult
                        showSettingsMenu(activity)
                    }
                    1 -> showLockedGroups(activity, locked)
                    2 -> create(activity) { showSettingsMenu(activity) }
                    3 -> MaterialAlertDialogBuilder(activity)
                        .setMessage(R.string.parental_disable_confirm)
                        .setPositiveButton(R.string.parental_disable) { _, _ ->
                            ParentalControl.disable()
                            Toast.makeText(activity, R.string.parental_disabled, Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun showLockedGroups(activity: Activity, locked: List<String>) {
        if (locked.isEmpty()) {
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.parental_groups_title)
                .setMessage(R.string.parental_groups_help)
                .setPositiveButton(R.string.ok) { _, _ -> showSettingsMenu(activity) }
                .show()
            return
        }
        val checked = BooleanArray(locked.size) { true }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.parental_groups_title)
            .setMultiChoiceItems(locked.toTypedArray(), checked) { _, i, isChecked -> checked[i] = isChecked }
            .setPositiveButton(R.string.save) { _, _ ->
                locked.forEachIndexed { i, g -> if (!checked[i]) ParentalControl.setGroupLocked(g, false) }
                showSettingsMenu(activity)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
