package net.positivestate.sendtosqueezebox;

import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.preference.PreferenceManager;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.URLDecoder;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SendToActivity extends AppCompatActivity {

    class Player {
        public String name;
        String MACaddress;

        Player(String name, String MACaddress) {
            this.name = name;
            this.MACaddress = MACaddress;
        }
    }
    List<Player> players;
    String url;
    SharedPreferences sharedPref;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        sharedPref = PreferenceManager.getDefaultSharedPreferences(this);
        Intent intent = getIntent();
        String action = intent.getAction();
        if(action.equals("android.intent.action.SEND")) {
            url = intent.getExtras().getString(Intent.EXTRA_TEXT);
        } else if(action.equals("android.intent.action.VIEW")){
            url = intent.getDataString();
        }

        GetPlayersTask playersTask = new GetPlayersTask();
        playersTask.execute();
        try {
            players = playersTask.get();
        } catch (Exception e) {
            e.printStackTrace();
        }

        if(players.size() == 0) {
            Toast toast = Toast.makeText(getApplicationContext(), "No Players Found!", Toast.LENGTH_LONG);
            toast.show();
            finish();
        } else if(players.size() == 1) {
            sendToSqueezebox(0);
        } else {
            AlertDialog.Builder builder = new AlertDialog.Builder(SendToActivity.this);
            ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1);
            for(Player player: players){
                adapter.add(player.name);
            }
            builder.setTitle("Which Squeezebox?");
            builder.setAdapter(adapter, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int item) {
                    sendToSqueezebox(item);
                }
            });
            AlertDialog dialog = builder.create();
            dialog.show();
        }

        //finish();
    }

    public void sendToSqueezebox(int chosen_player_index) {
        Player player = players.get(chosen_player_index);
        SendToSqueezeboxTask task = new SendToSqueezeboxTask();
        task.execute(player.MACaddress, convertURL(url));

        Toast toast = Toast.makeText(getApplicationContext(), "Sent  to " + player.name, Toast.LENGTH_LONG);
        toast.show();

    }

    public static String convertURL(String url) {
        Pattern googlemusic_pattern = Pattern.compile(
                "https://play.google.com/music(/r)?/m/(A|B|T|R)([^\\?]+)\\??(.*)",
                Pattern.CASE_INSENSITIVE);
        Matcher googlemusic_matcher = googlemusic_pattern.matcher(url);

        Pattern youtube_pattern = Pattern.compile(
                "https?://(?:[0-9A-Z-]+\\.)?(?:youtu\\.be/|youtube\\.com\\S*[^\\w\\-\\s])([\\w\\-]{11})(?=[^\\w\\-]|$)(?![?=&+%\\w]*(?:['\"][^<>]*>|</a>))[?=&+%\\w]*",
                Pattern.CASE_INSENSITIVE);
        Matcher youtube_matcher = youtube_pattern.matcher(url);

        if (googlemusic_matcher.matches()) {
            switch (googlemusic_matcher.group(2)) {
                case "A":  // artist
                    return "googlemusic:artist:A" + googlemusic_matcher.group(3);
                case "B":  // radio or album
                    if (googlemusic_matcher.group(1) != null) { //radio
                        return "googlemusic:station:B" + googlemusic_matcher.group(3);
                    } else {
                        return "googlemusic:album:B" + googlemusic_matcher.group(3);
                    }
                case "T":  // track
                    return "googlemusic:track:T" + googlemusic_matcher.group(3);
                case "R":  // album
                    return "googlemusic:album:R" + googlemusic_matcher.group(3);
            }
        } else if (youtube_matcher.matches()) {
            return "youtube://" + youtube_matcher.group(1);
        }
        return url;
    }

    private class GetPlayersTask extends AsyncTask<Void, Void, List<Player>> {
        protected List<Player> doInBackground(Void... args) {
            List<Player> players = new ArrayList<>();
            try {
                final String address = sharedPref.getString("server_address", "");
                final String port = sharedPref.getString("server_port", "");
                Socket socket = new Socket(address, Integer.parseInt(port));
                PrintWriter output = new PrintWriter(socket.getOutputStream(), true);
                BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                output.println("player count ?");

                String response = input.readLine();
                int playerCount = 0;
                if(response.substring(0,13).equals("player count ")) {
                    String doo = response.substring(13);
                    playerCount = Integer.parseInt(doo);
                } else {
                    //throw "Error contacting server";
                }
                for(int i=0; i<playerCount; i++) {
                    String playerMAC = "";
                    String playerName = "";
                    output.println("player id " + i + " ?");
                    response = input.readLine();
                    if(response.substring(0,11).equals("player id " + i)) { //TODO - handle 2-digits?
                        playerMAC = URLDecoder.decode(response.substring(12), "UTF-8");
                    } // TODO - check for empty response

                    output.println(" player name " + i + " ?");
                    response = input.readLine();
                    if(response.substring(0,13).equals("player name " + i)) { //TODO - handle 2-digits?
                        playerName = URLDecoder.decode(response.substring(14), "UTF-8");
                    } // TODO - check for empty response
                    players.add(new Player (playerName, playerMAC));
                }

                output.close();
            } catch (UnknownHostException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
            return players;
        }
    }
    private class SendToSqueezeboxTask extends AsyncTask<String, Void, Void> {
        protected Void doInBackground(String... args) {
            try {
                final String playerMAC = args[0];
                final String mediaURL = args[1];
                final String address = sharedPref.getString("server_address", "");
                final String port = sharedPref.getString("server_port", "");
                Socket socket = new Socket(address, Integer.parseInt(port));
                PrintWriter output = new PrintWriter(socket.getOutputStream(), true);
                output.println(playerMAC + " playlist play " + mediaURL);
                //output.flush();
                output.close();
            } catch (UnknownHostException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
            finish();
            return null;
        }
    }
}
