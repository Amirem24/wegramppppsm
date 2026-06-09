package com.example.ui.components

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.MultiplePermissionsState
import com.google.accompanist.permissions.rememberMultiplePermissionsState

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun PermissionsRequestScreen(onPermissionsGranted: () -> Unit) {
    val permissions = buildList {
        add(android.Manifest.permission.ACCESS_FINE_LOCATION)
        add(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        add(android.Manifest.permission.ACCESS_WIFI_STATE)
        add(android.Manifest.permission.CHANGE_WIFI_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(android.Manifest.permission.BLUETOOTH_ADVERTISE)
            add(android.Manifest.permission.BLUETOOTH_CONNECT)
            add(android.Manifest.permission.BLUETOOTH_SCAN)
        } else {
            add(android.Manifest.permission.BLUETOOTH)
            add(android.Manifest.permission.BLUETOOTH_ADMIN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(android.Manifest.permission.NEARBY_WIFI_DEVICES)
        }
    }

    val permissionState = rememberMultiplePermissionsState(permissions = permissions)

    if (permissionState.allPermissionsGranted) {
        LaunchedEffect(Unit) {
            onPermissionsGranted()
        }
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
                Text("Nearby messaging requires location and network permissions.")
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { permissionState.launchMultiplePermissionRequest() }) {
                    Text("Grant Permissions")
                }
            }
        }
    }
}
