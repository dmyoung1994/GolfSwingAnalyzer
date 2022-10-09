package com.golfapp.swingly.activity

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupWithNavController
import androidx.room.Room
import androidx.room.RoomDatabase
import com.golfapp.swingly.R
import com.golfapp.swingly.entities.AppDatabase
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.floatingactionbutton.FloatingActionButton

class MainActivity : AppCompatActivity() {

    private lateinit var navController: NavController
    private lateinit var appBarConfiguration: AppBarConfiguration

    private lateinit var appDb: RoomDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        appDb = Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java, "swingly-db"
        ).build()

        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController

        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_navigation_view)
        bottomNav.background = null
        bottomNav.menu.getItem(2).isEnabled = false
        bottomNav.setupWithNavController(navController)

        val coachNavButton = findViewById<FloatingActionButton>(R.id.coach_button)
        coachNavButton?.setOnClickListener{
            navController.navigate(R.id.recorder)
        }
    }
}