package com.pic.catcher.adapter

import java.util.concurrent.CopyOnWriteArrayList


abstract class CommonListAdapter<E, VH : AbsListAdapter.ViewHolder> : AbsListAdapter<VH>() {

    open val dataList: MutableList<E> = CopyOnWriteArrayList()

    open fun setData(data: List<E>): CommonListAdapter<E, VH> {
        this.dataList.clear()
        this.dataList.addAll(data)
        notifyDataSetChanged()
        return this
    }

    open fun updateDataAt(data: List<E>) {
        this.dataList.clear()
        this.dataList.addAll(data)
        notifyDataSetChanged()
    }

    open fun updateDataAt(ele: E) {
        val index = dataList.indexOf(ele)
        updateDataAt(index)
    }

    open fun updateDataAt(position: Int) {
        if (position < 0 || position >= dataList.size) {
            return
        }
        notifyDataSetChanged()
    }

    open fun addData(data: List<E>): CommonListAdapter<E, VH> {
        this.dataList.addAll(data)
        return this
    }

    open fun addData(vararg ele: E): CommonListAdapter<E, VH> {
        this.dataList.addAll(ele)
        return this
    }

    open fun remove(data: List<E>): CommonListAdapter<E, VH> {
        this.dataList.removeAll(data)
        return this
    }

    open fun remove(vararg elements: E): CommonListAdapter<E, VH> {
        this.dataList.removeAll(elements.toSet())
        return this
    }

    open fun removeAt(index: Int): CommonListAdapter<E, VH> {
        this.dataList.removeAt(index)
        return this
    }

    open fun getData(): MutableList<E> {
        return dataList
    }

    override fun getItem(position: Int): E? {
        if (position < 0 || position >= dataList.size) return null
        return dataList[position]
    }


    override fun getCount(): Int {
        return dataList.size
    }

    override fun getItemId(position: Int): Long {
        val item = getItem(position) ?: return position.toLong()
        // 建立基于业务语义的唯一指纹。即使列表重新构建，只要标题没变，ID 就保持一致。
        // 这能让 TransitionManager 准确追踪到视图的移动轨迹，消除闪烁和乱飞。
        val identity = when (item) {
            is com.pic.catcher.bean.GroupItem -> "G_" + item.title
            is com.pic.catcher.bean.SwitchItem -> "S_" + item.title
            is com.pic.catcher.bean.TextItem -> "T_" + item.name
            is com.pic.catcher.bean.EditItem -> "E_" + item.name
            is com.pic.catcher.bean.SliderItem -> "SL_" + item.title
            is com.pic.catcher.bean.SpinnerItem -> "SP_" + item.title
            is com.pic.catcher.bean.NoMediaItem -> "NM_" + item.title
            is com.pic.catcher.bean.ResolutionItem -> "R_" + item.title
            is com.pic.catcher.bean.QuickPresetItem -> "QP"
            else -> item.javaClass.simpleName + "_" + position
        }
        return identity.hashCode().toLong()
    }

    override fun hasStableIds(): Boolean {
        return true
    }

}
