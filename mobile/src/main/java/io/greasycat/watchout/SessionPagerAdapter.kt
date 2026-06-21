package io.greasycat.watchout

import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

/** One page per session id. Stable ids let the pager keep position as sessions are added. */
class SessionPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {

    private var ids: List<String> = emptyList()

    /** Returns true if the id list changed (caller should refresh dots). */
    fun submit(newIds: List<String>): Boolean {
        if (newIds == ids) return false
        ids = newIds
        notifyDataSetChanged()
        return true
    }

    fun ids(): List<String> = ids

    override fun getItemCount(): Int = ids.size

    override fun createFragment(position: Int) = SessionFragment.newInstance(ids[position])

    override fun getItemId(position: Int): Long = ids[position].hashCode().toLong()

    override fun containsItem(itemId: Long): Boolean = ids.any { it.hashCode().toLong() == itemId }
}
