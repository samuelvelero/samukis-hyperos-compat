package xyz.samukis.hyperoscompat.bridge

import androidx.annotation.StringRes
import xyz.samukis.hyperoscompat.R

/** Grouping for the apps list. Declaration order is display order. */
enum class BridgeCategory(@StringRes val titleRes: Int) {
    CALLS(R.string.cat_calls),
    TRANSPORT(R.string.cat_transport),
    TRANSFERS(R.string.cat_transfers),
}
