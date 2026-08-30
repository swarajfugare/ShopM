package com.matoshree.shopmanager

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import androidx.navigation.compose.rememberNavController
import com.matoshree.shopmanager.data.local.AppDatabase
import com.matoshree.shopmanager.security.PinManager
import com.matoshree.shopmanager.ui.navigation.AppNavGraph
import com.matoshree.shopmanager.ui.theme.MatoshreeTheme

class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val database = AppDatabase.getInstance(this)
        val pinManager = PinManager(this)

        setContent {
            MatoshreeTheme {
                val navController = rememberNavController()
                AppNavGraph(
                    navController = navController,
                    database = database,
                    pinManager = pinManager
                )
            }
        }
    }
}
