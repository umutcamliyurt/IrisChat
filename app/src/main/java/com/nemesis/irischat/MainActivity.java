package com.nemesis.irischat;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.pircbotx.Configuration;
import org.pircbotx.PircBotX;
import org.pircbotx.hooks.ListenerAdapter;
import org.pircbotx.hooks.events.MessageEvent;
import org.pircbotx.exception.IrcException;

import java.io.IOException;

public class MainActivity extends AppCompatActivity {

    private EditText serverInput, portInput, nicknameInput, channelInput, chatInput;
    private TextView chatDisplay;
    private Button connectButton, sendButton;
    private PircBotX bot;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize views
        serverInput = findViewById(R.id.serverInput);
        portInput = findViewById(R.id.portInput);
        nicknameInput = findViewById(R.id.nicknameInput);
        channelInput = findViewById(R.id.channelInput);
        chatInput = findViewById(R.id.chatInput);
        chatDisplay = findViewById(R.id.chatDisplay);
        connectButton = findViewById(R.id.connectButton);
        sendButton = findViewById(R.id.sendButton);

        // Load saved settings
        SharedPreferences sharedPreferences = getSharedPreferences("IrcSettings", MODE_PRIVATE);
        serverInput.setText(sharedPreferences.getString("server", ""));
        portInput.setText(String.valueOf(sharedPreferences.getInt("port", 6667)));
        nicknameInput.setText(sharedPreferences.getString("nickname", ""));
        channelInput.setText(sharedPreferences.getString("channel", ""));

        connectButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                connectToIrc();
            }
        });

        sendButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendMessage();
            }
        });
    }

    private void connectToIrc() {
        String server = serverInput.getText().toString();
        int port = Integer.parseInt(portInput.getText().toString());
        String nickname = nicknameInput.getText().toString();
        String channel = channelInput.getText().toString();

        // Save settings to SharedPreferences
        SharedPreferences sharedPreferences = getSharedPreferences("IrcSettings", MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString("server", server);
        editor.putInt("port", port);
        editor.putString("nickname", nickname);
        editor.putString("channel", channel);
        editor.apply();

        try {
            Configuration configuration = new Configuration.Builder()
                    .setName(nickname)
                    .addServer(server, port)
                    .addAutoJoinChannel(channel)
                    .addListener(new ListenerAdapter() {
                        @Override
                        public void onMessage(MessageEvent event) {
                            String message = event.getUser().getNick() + ": " + event.getMessage();
                            runOnUiThread(() -> chatDisplay.append(message + "\n"));
                        }
                    })
                    .buildConfiguration();

            bot = new PircBotX(configuration);
            new Thread(() -> {
                try {
                    bot.startBot();
                } catch (IOException | IrcException e) {
                    e.printStackTrace();
                }
            }).start();

            Toast.makeText(this, "Connected to IRC", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Error connecting: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void sendMessage() {
        String message = chatInput.getText().toString();
        String channel = channelInput.getText().toString();

        if (bot != null && !message.isEmpty()) {
            String sentMessage = "You: " + message;
            chatDisplay.append(sentMessage + "\n");

            new Thread(() -> {
                try {
                    bot.send().message(channel, message);
                    runOnUiThread(() -> chatInput.setText(""));
                } catch (Exception e) {
                    e.printStackTrace();
                    runOnUiThread(() ->
                            Toast.makeText(MainActivity.this, "Error sending message: " + e.getMessage(), Toast.LENGTH_LONG).show());
                }
            }).start();
        }
    }
}
