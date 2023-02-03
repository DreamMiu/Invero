package cc.trixey.invero.ui.common

/**
 * Invero
 * cc.trixey.invero.ui.common.StorageMode
 *
 * @author Arasple
 * @since 2023/1/11 21:47
 *
 * USE (always restore)
 *  - CLEAN & USE
 *  - NOT CLEAN & USE
 *
 * NOT USE (not restore, no exceed support, not clean)
 *
 */
data class StorageMode(val overridePlayerInventory: Boolean = true, val alwaysClean: Boolean = true) {

    val shouldClean: Boolean
        get() = overridePlayerInventory && alwaysClean

}