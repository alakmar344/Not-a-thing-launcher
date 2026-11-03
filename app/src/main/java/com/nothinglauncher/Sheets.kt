package com.nothinglauncher

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import com.nothinglauncher.databinding.DialogConfirmBinding
import com.nothinglauncher.databinding.DialogEditNoteBinding

/** Floating bottom sheets & dialogs in the v10 visual language. */
object Sheets {

    fun create(context: Context, content: View): Dialog {
        val dialog = Dialog(context, R.style.Theme_NothingLauncher_Sheet)
        dialog.setContentView(content)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.apply {
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
        }
        return dialog
    }

    /** Shows [dialog] unless its activity is going away (avoids BadTokenException). */
    fun showSafely(context: Context, dialog: Dialog) {
        var c: Context? = context
        while (c is ContextWrapper && c !is Activity) c = c.baseContext
        val activity = c as? Activity
        if (activity != null && (activity.isFinishing || activity.isDestroyed)) return
        try {
            dialog.show()
        } catch (_: WindowManager.BadTokenException) {
        }
    }

    fun showTextInput(
        context: Context,
        title: String,
        hint: String,
        initial: String,
        maxLength: Int = 48,
        onSave: (String) -> Unit
    ): Dialog {
        val b = DialogEditNoteBinding.inflate(LayoutInflater.from(context))
        val dialog = create(context, b.root)
        b.noteTitle.text = title.uppercase()
        b.noteInput.hint = hint
        b.noteInput.inputType =
            android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
        b.noteInput.filters = arrayOf(android.text.InputFilter.LengthFilter(maxLength))
        b.noteInput.setText(initial)
        b.noteInput.setSelection(b.noteInput.text.length)
        b.noteCancel.setOnClickListener { dialog.dismiss() }
        b.noteSave.setOnClickListener {
            dialog.dismiss()
            onSave(b.noteInput.text.toString().trim())
        }
        setupTextInput(dialog, b.noteInput) { b.noteSave.performClick() }
        showSafely(context, dialog)
        return dialog
    }

    fun showConfirm(context: Context, title: Int, body: Int, ok: Int, onOk: () -> Unit): Dialog {
        val b = DialogConfirmBinding.inflate(LayoutInflater.from(context))
        val dialog = create(context, b.root)
        b.confirmTitle.setText(title)
        b.confirmBody.setText(body)
        b.confirmOk.setText(ok)
        b.confirmCancel.setOnClickListener { dialog.dismiss() }
        b.confirmOk.setOnClickListener { dialog.dismiss(); onOk() }
        showSafely(context, dialog)
        return dialog
    }

    /** Opens the keyboard with the sheet and lets the IME "Done" key confirm it. */
    private fun setupTextInput(dialog: Dialog, input: EditText, onDone: () -> Unit) {
        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
        input.requestFocus()
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                onDone()
                true
            } else {
                false
            }
        }
    }
}
