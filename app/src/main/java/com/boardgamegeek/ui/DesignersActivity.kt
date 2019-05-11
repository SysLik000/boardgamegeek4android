package com.boardgamegeek.ui

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProviders
import com.boardgamegeek.R
import com.boardgamegeek.extensions.setActionBarCount
import com.boardgamegeek.ui.viewmodel.DesignsViewModel

class DesignersActivity : TopLevelSinglePaneActivity() {
    private var numberOfDesigners = -1
    private var sortBy = DesignsViewModel.SortType.NAME

    private val viewModel: DesignsViewModel by lazy {
        ViewModelProviders.of(this).get(DesignsViewModel::class.java)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.designers.observe(this, Observer {
            numberOfDesigners = it?.size ?: 0
            invalidateOptionsMenu()
        })
        viewModel.sort.observe(this, Observer {
            sortBy = it.sortType
            invalidateOptionsMenu()
        })
    }

    override fun onCreatePane(): Fragment = DesignersFragment.newInstance()

    override val optionsMenuId = R.menu.designers

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        super.onPrepareOptionsMenu(menu)
        when (sortBy) {
            DesignsViewModel.SortType.NAME -> menu.findItem(R.id.menu_sort_name)
            DesignsViewModel.SortType.ITEM_COUNT -> menu.findItem(R.id.menu_sort_item_count)
        }.apply {
            isChecked = true
            menu.setActionBarCount(R.id.menu_list_count, numberOfDesigners, getString(R.string.by_prefix, title))
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_sort_name -> {
                viewModel.sort(DesignsViewModel.SortType.NAME)
                return true
            }
            R.id.menu_sort_item_count -> {
                viewModel.sort(DesignsViewModel.SortType.ITEM_COUNT)
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override val navigationItemId = R.id.designers
}
