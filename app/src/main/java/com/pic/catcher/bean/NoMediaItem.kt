package com.pic.catcher.bean

class NoMediaItem(
    title: CharSequence,
    checked: Boolean = false,
    desc: CharSequence? = null,
    val onGenerate: (() -> Unit)? = null,
    val onRemove: (() -> Unit)? = null
) : UiItem() {
    var title: CharSequence by observableProperty(title, "title")
    var checked: Boolean by observableProperty(checked, "checked")
    var desc: CharSequence? by observableProperty(desc, "desc")
}
