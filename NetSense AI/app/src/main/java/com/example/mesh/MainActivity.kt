package com.example.mesh

import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private lateinit var meshManager: MeshManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(android.R.layout.simple_list_item_1)

        meshManager = MeshManager("local-node")

        meshManager.startDiscovery()
        meshManager.addPeer("peer1")

        val sendButton = Button(this).apply {
            text = "Send"
            setOnClickListener {
                meshManager.sendMessage("peer1", "hello", 8)
            }
        }
        setContentView(sendButton)
    }

    override fun onDestroy() {
        super.onDestroy()
        meshManager.stopDiscovery()
    }
}
