package cc.trixey.invero.ui.bukkit

import cc.trixey.invero.common.message.createInventoryPaper
import cc.trixey.invero.ui.bukkit.api.isRegistered
import cc.trixey.invero.ui.bukkit.nms.handler
import cc.trixey.invero.ui.bukkit.panel.CraftingPanel
import cc.trixey.invero.ui.bukkit.util.clickType
import cc.trixey.invero.ui.bukkit.util.synced
import kotlinx.coroutines.*
import org.bukkit.entity.Player
import org.bukkit.event.inventory.*
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import taboolib.common.platform.function.submit
import taboolib.common.platform.function.submitAsync

/**
 * Invero
 * cc.trixey.invero.ui.bukkit.InventoryVanilla
 *
 * @author Arasple
 * @since 2023/1/20 13:13
 */
class InventoryVanilla(override val window: BukkitWindow) : ProxyBukkitInventory {

    val container: Inventory = createContainer()
    
    // 协程作用域，仅使用IO调度器，不使用Main调度器
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private fun createContainer(): Inventory {
        return if (containerType.isOrdinaryChest)
            createInventoryPaper(Holder(window), containerType.containerSize, inventoryTitle)
        else try {
            createInventoryPaper(Holder(window), InventoryType.valueOf(containerType.bukkitType), inventoryTitle)
        } catch (e: Throwable) {
            error("Not supported inventory type (${containerType.bukkitType}) yet")
        }
    }

    override val hidePlayerInventory: Boolean by lazy { window.hidePlayerInventory }

    override var containerId: Int = -1

    private var playerInventoryItems =
        if (hidePlayerInventory) arrayOfNulls<ItemStack?>(36)
        else viewer?.copyStorage() ?: arrayOfNulls(36)
        // 设置玩家背包
        set(value) {
            field = value
            updatePlayerInventory()
        }


    override fun isVirtual(): Boolean {
        return false
    }

    private var clickCallback: (InventoryClickEvent) -> Boolean = { true }
    private var moveCallback: (InventoryClickEvent) -> Boolean = { true }
    private var collectCallback: (InventoryClickEvent) -> Boolean = { true }
    private var dragCallback: (InventoryDragEvent) -> Boolean = { true }

    fun updatePlayerInventory(vararg slots: Int) {
        val viewer = viewer ?: return

        if (!hidePlayerInventory || window.anyIOPanel) return

        // 使用协程异步处理物品栏更新，避免主线程阻塞
        coroutineScope.launch {
            try {
                val itemsToUpdate = if (slots.isEmpty()) {
                    playerInventoryItems
                        .mapIndexed { index, itemStack -> (index + containerSize) to itemStack }
                        .toMap()
                } else {
                    slots.map { containerSize + it to playerInventoryItems[it] }.toMap()
                }
                
                // 使用taboolib的同步任务回到主线程
                submit {
                    if (viewer.isOnline && isViewing()) {
                        handler.sendWindowSetSlots(viewer, containerId, itemsToUpdate)
                    }
                }
            } catch (e: Exception) {
                // 异常处理
            }
        }
    }

    fun onClick(handler: (InventoryClickEvent) -> Boolean): InventoryVanilla {
        clickCallback = handler
        return this
    }

    fun onItemsMove(handler: (InventoryClickEvent) -> Boolean): InventoryVanilla {
        moveCallback = handler
        return this
    }

    fun onItemsCollect(handler: (InventoryClickEvent) -> Boolean): InventoryVanilla {
        collectCallback = handler
        return this
    }

    fun onDrag(handler: (InventoryDragEvent) -> Boolean): InventoryVanilla {
        dragCallback = handler
        return this
    }

    override fun clear(slots: Collection<Int>) {
        slots.forEach { set(it, null) }
    }

    override fun get(slot: Int): ItemStack? {
        return if (slot >= containerSize) {
            playerInventoryItems[slot - containerSize]
        } else {
            container.getItem(slot)
        }
    }

    override fun set(slot: Int, itemStack: ItemStack?) {
        synced {
            if (slot >= containerSize) {
                playerInventoryItems[slot - containerSize] = itemStack
                // 异步处理玩家物品栏更新
                submitAsync {
                    updatePlayerInventory(slot - containerSize)
                }
            } else {
                container.setItem(slot, itemStack)
                // 异步处理更新
                submitAsync {
                    updatePlayerInventory()
                }
            }
        }
    }

    override fun isViewing(): Boolean {
        val viewer = window.viewer.get<Player>()
        // 使用NMS方式替代直接比较inventory，避免版本兼容性问题
        return viewer != null && viewer.isOnline && handler.getActiveContainerId(viewer) == containerId && containerId != -1
    }

    override fun open() {
        val viewer = viewer ?: return

        viewer.openInventory(container)
        containerId = handler.getContainerId(viewer)
        updatePlayerInventory()

        // 使用协程定期更新玩家物品栏，减少更新频率
        if (!hidePlayerInventory && !window.anyIOPanel) {
            coroutineScope.launch {
                while (isActive && window.isViewing()) {
                    delay(1000) // 降低更新频率到1秒一次
                    if (!isActive || !window.isViewing()) break
                    
                    // 使用taboolib的同步任务获取物品
                    val newItems = CompletableDeferred<Array<ItemStack?>>()
                    submit {
                        if (viewer.isOnline) {
                            newItems.complete(viewer.copyStorage())
                        } else {
                            newItems.complete(arrayOfNulls(36))
                        }
                    }
                    
                    val items = newItems.await()
                    if (items.isNotEmpty()) {
                        // 回到主线程更新物品
                        submit {
                            playerInventoryItems = items
                        }
                    }
                }
            }
        }
    }

    // 清理协程资源
    fun dispose() {
        coroutineScope.cancel()
    }

    fun handleClick(e: InventoryClickEvent) {
        // 默认取消事件
        e.isCancelled = true
        if (!clickCallback(e)) return
        // 点击的坐标
        val slot = e.rawSlot
        // 如果点击玩家背包容器
        if (slot >= containerSize) {
            if (!hidePlayerInventory && window.anyIOPanel) {
                e.isCancelled = false
                return
            }
        }
        // 转化为 x,y 定位
        val pos = window.scale.convertToPosition(slot)
        // 查找有效面板
        window
            .panels
            .sortedByDescending { it.weight }
            .forEach {
                if (pos in it.area) {
                    val converted = e.clickType
                    if (it.runClickCallbacks(pos, converted, e)) {
                        // 使用协程处理点击操作
                        submitAsync {
                            it.handleClick(pos - it.locate, converted, e)
                        }
                    }
                    return
                }
            }
    }

    fun handleDrag(e: InventoryDragEvent) {
        // 默认取消
        e.isCancelled = true
        if (!dragCallback(e)) return
        // 寻找 Panel 交接
        val handler = window
            .panels
            .sortedBy { it.locate }
            .sortedByDescending { it.weight }
            .find {
                e.rawSlots.all { slot -> window.scale.convertToPosition(slot) in it.area }
            }
        // 传递给 Panel 处理
        if (handler != null) {
            val affected = e.rawSlots.map { window.scale.convertToPosition(it) }
            // 使用协程处理拖拽操作
            submitAsync {
                handler.handleDrag(affected, e)
            }
        }
    }

    fun handleItemsMove(e: InventoryClickEvent) {
        // 默认取消
        e.isCancelled = true
        if (!collectCallback(e)) return
        
        val slot = e.rawSlot
        // playerInventory -> IO Panel
        if (slot > window.type.slotsContainer.last) {
            if (hidePlayerInventory || !window.anyIOPanel) return handleClick(e)
            val insertItem = e.currentItem?.clone() ?: return handleClick(e)
            window
                .getPanelsRecursively()
                .filterIsInstance<CraftingPanel>()
                .sortedBy { it.locate }
                .sortedByDescending { it.weight }
                .also { if (it.isEmpty()) return handleClick(e) }
                .forEach {
                    val previous = insertItem.amount
                    val result = it.insert(insertItem.clone())
                    insertItem.amount = result

                    if (previous != result) {
                        // 异步处理渲染
                        submitAsync {
                            it.renderStorage()
                            it.runCallback()
                        }
                    }
                    if (result <= 0) return@forEach
                }
            e.currentItem?.amount = insertItem.amount
        }
        // IO Panel -> playerInventory
        else if (!hidePlayerInventory && window.anyIOPanel) {
            val clickedSlot = window.scale.convertToPosition(slot)

            window
                .panels
                .sortedBy { it.locate }
                .sortedByDescending { it.weight }
                .find { it is CraftingPanel && window.scale.convertToPosition(e.rawSlot) in it.area }
                ?.handleItemsMove(clickedSlot, e)
                .let { if (it == null) return handleClick(e) }
        } else {
            return handleClick(e)
        }
    }

    fun handleItemsCollect(e: InventoryClickEvent) {
        // 默认取消
        e.isCancelled = true
        if (!collectCallback(e)) return
        // 暂时未写双击收集物品的逻辑...
        return handleClick(e)
    }

    fun handleOpenEvent(e: InventoryOpenEvent) {

    }

    fun handleCloseEvent(e: InventoryCloseEvent) {
        if (window.isRegistered()) {
            dispose() // 清理协程资源
            window.close(doCloseInventory = false, updateInventory = false)
        }
    }


    class Holder(val window: BukkitWindow) : InventoryHolder {

        internal val inventory: Inventory
            get() = (window.inventory as InventoryVanilla).container

        override fun getInventory(): Inventory {
            return inventory
        }
    }
}
