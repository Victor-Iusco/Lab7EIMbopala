package ro.pub.cs.systems.eim.bluetoothchatapp

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.util.*

class MainActivity : AppCompatActivity() {

    private var bluetoothAdapter: BluetoothAdapter? = null
    private lateinit var chatArrayAdapter: ArrayAdapter<String>
    private var chatMessages = mutableListOf<String>()

    private var selectedDevice: BluetoothDevice? = null

    private lateinit var messageEditText: EditText
    private lateinit var sendButton: Button
    private lateinit var listDevicesButton: Button

    private var acceptThread: AcceptThread? = null
    private var connectThread: ConnectThread? = null
    private var connectedThread: ConnectedThread? = null

    companion object {
        private const val TAG = "MainActivity"
        val MY_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val REQUEST_ENABLE_BT = 1
        private const val REQUEST_PERMISSIONS = 2
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize views
        initViews()

        // Initialize Bluetooth
        initBluetooth()

        // Request permissions
        checkPermissions()

        // Start server socket for incoming connections
        // Permissions may not be granted yet on the first launch, in which case
        // the call is a no-op and the server is started from onRequestPermissionsResult.
        startServer()

        // Send message using ConnectedThread
        sendMessage()
    }

    // Initializes all the views defined in activity_main.xml
    private fun initViews() {
        // Conectăm componentele din XML la codul Kotlin
        val chatListView: ListView = findViewById(R.id.chatListView)
        messageEditText = findViewById(R.id.messageEditText)
        sendButton = findViewById(R.id.sendButton)
        listDevicesButton = findViewById(R.id.listDevicesButton)

        // Setăm un eveniment pentru butonul de listare a dispozitivelor
        listDevicesButton.setOnClickListener { listPairedDevices() }

        // Pregătim o listă pentru mesaje
        chatMessages = mutableListOf()

        // Creăm un adaptor care transformă lista noastră de mesaje în elemente vizuale
        chatArrayAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, chatMessages)

        // Modificările din adaptor se vor vedea imediat în interfața grafică
        chatListView.adapter = chatArrayAdapter
    }

    // Initializes the Bluetooth adapter
    private fun initBluetooth() {
        // Vom lua o referință la adaptorul de bluetooth de pe telefon.
        // Putem vedea acest adaptor ca o interfață cu driver-ul de bluetooth.
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()

        // Verificăm dacă Bluetooth este disponibil
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth is not available.", Toast.LENGTH_LONG).show()
            finish() // Închidem aplicația dacă Bluetooth nu este disponibil
        }
    }

    // Requests the Bluetooth runtime permissions
    private fun checkPermissions() {
        val permissions = mutableListOf<String>()

        // Pentru Android 12 sau mai nou, folosim permisiuni specifice Bluetooth
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            // Pentru versiuni mai vechi, accesul la locație este necesar
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        // Cerem permisiunile utilizatorului
        ActivityCompat.requestPermissions(this, permissions.toTypedArray(), REQUEST_PERMISSIONS)
    }

    // Handles the permission results and activates Bluetooth programmatically
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            var permissionGranted = true

            // Verificăm dacă toate permisiunile au fost acordate
            for (result in grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    permissionGranted = false
                    break
                }
            }

            if (!permissionGranted) {
                Toast.makeText(this, "Permissions required for Bluetooth operation.", Toast.LENGTH_LONG).show()
                finish()
                return
            }

            val adapter = bluetoothAdapter ?: return

            // Activăm Bluetooth dacă nu este deja activat
            if (!adapter.isEnabled) {
                val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    return
                }
                startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
            } else {
                startServer()
            }
        }
    }

    // Handles the Bluetooth enable result
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ENABLE_BT) {
            if (resultCode != RESULT_OK) {
                Toast.makeText(this, "Bluetooth must be enabled to continue.", Toast.LENGTH_LONG).show()
                finish()
                return
            }
            // Bluetooth is now enabled, we can listen for incoming connections
            startServer()
        }
    }

    // Shows a dialog for selecting a paired device
    private fun listPairedDevices() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Bluetooth Connect permission not granted.", Toast.LENGTH_SHORT).show()
            return
        }

        val adapter = bluetoothAdapter ?: return

        // Obținem dispozitivele împerecheate
        val pairedDevices: Set<BluetoothDevice> = adapter.bondedDevices
        val deviceList = mutableListOf<String>()
        val devices = mutableListOf<BluetoothDevice>()

        // Le adăugăm în listă
        for (device in pairedDevices) {
            deviceList.add("${device.name}\n${device.address}")
            devices.add(device)
        }

        // Afișăm dispozitivele într-un dialog
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Select Device")

        val deviceArrayAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, deviceList)

        // Tratăm situația în care un dispozitiv este apăsat în cadrul dialogului
        builder.setAdapter(deviceArrayAdapter) { _, which ->
            selectedDevice = devices[which]

            // Deschidem un thread de comunicare
            connectThread = ConnectThread(this, adapter, selectedDevice, MY_UUID)
            connectThread?.start()
        }

        builder.show()
    }

    // Starts the server socket that listens for incoming connections
    private fun startServer() {
        if (acceptThread != null) {
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "Cannot start server: BLUETOOTH_CONNECT permission not granted.")
            return
        }

        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) {
            Log.d(TAG, "Cannot start server: Bluetooth is disabled.")
            return
        }

        // Inițializăm AcceptThread, care va gestiona conexiunile primite
        acceptThread = AcceptThread(this, adapter, MY_UUID)
        acceptThread?.start()
        Log.d(TAG, "Bluetooth server started, listening for incoming connections.")
        Toast.makeText(this, "Server started. Waiting for a connection...", Toast.LENGTH_SHORT).show()
    }

    // Sends a message to the connected device
    private fun sendMessage() {
        sendButton.setOnClickListener {
            // Preluăm mesajul introdus de utilizator
            val message = messageEditText.text.toString()

            // Verificăm dacă mesajul nu este gol și conexiunea este activă
            if (message.isEmpty()) {
                return@setOnClickListener
            }

            val thread = connectedThread
            if (thread == null) {
                Toast.makeText(this, "No connected device.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Trimitem mesajul ca un array de bytes
            thread.write(message.toByteArray())

            // Golește câmpul de text
            messageEditText.setText("")

            // Adaugă mesajul trimis în interfață
            addChatMessage("Me: $message")
        }
    }

    // Update the UI with a new message
    fun addChatMessage(message: String) {
        runOnUiThread {
            chatMessages.add(message)
            chatArrayAdapter.notifyDataSetChanged()
        }
    }

    fun manageConnectedSocket(socket: BluetoothSocket) {
        connectedThread?.cancel()
        connectedThread = ConnectedThread(this, socket).apply { start() }

        // Odată ce o conexiune este stabilită, nu mai este nevoie să ascultăm
        acceptThread?.cancel()
        acceptThread = null
    }

    // Cleans up the threads when the activity is destroyed
    override fun onDestroy() {
        super.onDestroy()
        // Închidem AcceptThread dacă rulează
        acceptThread?.cancel()
        acceptThread = null

        // Închidem ConnectThread dacă rulează
        connectThread?.cancel()
        connectThread = null

        // Închidem ConnectedThread dacă rulează
        connectedThread?.cancel()
        connectedThread = null
    }
}