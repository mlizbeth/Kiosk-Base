package io.valhala.base;

import android.Manifest;
import android.accounts.Account;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.AsyncTask;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.Scope;
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.SheetsScopes;
import com.google.api.services.sheets.v4.model.AppendValuesResponse;
import com.google.api.services.sheets.v4.model.ValueRange;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private final String spreadsheetId = "";
    private Account account;
    private String barcode;
    private NetHttpTransport HTTP_TRANSPORT;
    private GoogleAccountCredential credential;
    private GoogleSignInAccount signInAccount;
    private static final int frequency = 44100;
    private static final int channelConfig = AudioFormat.CHANNEL_CONFIGURATION_MONO;
    private static final int audioEncoding = AudioFormat.ENCODING_PCM_16BIT;
    private int bufferSize;
    private AudioRecord audioRecord;
    private AsyncTask<String, Void, Boolean> writeTask;
    private AsyncTask<Void, Void, ParseResult> task;
    private static final int REQUEST_CODE_ASK_PERM = 1;
    private static final int REQUEST_CODE_SIGN_IN = 0;
    private static final String[] REQUIRED_PERMISSION = new String[] {Manifest.permission.RECORD_AUDIO,Manifest.permission.INTERNET, Manifest.permission.GET_ACCOUNTS};
    private static final String APPLICATION_NAME = "";
    private static final JsonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();
    private Sheets service;
    private Student student;
    private Button submitBtn;
    private String reason;
    private boolean valid;

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        checkPermissions();

        try {
            bufferSize = AudioRecord.getMinBufferSize(frequency, channelConfig, audioEncoding) * 8;
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, frequency, channelConfig, audioEncoding, bufferSize);
        } catch(Exception e) {}

        if(account == null) {new Login().execute();}
        setContentView(R.layout.activity_main);

        init();
    }

    private void init() {
        student = new Student();
    }

    private void verifyID(String data) {
        barcode = "";
        String[] delimiter = {";", "?"};
        int min_length = 9, max_length = 11;
        if(data.length() == min_length) {
            barcode = data.substring(data.indexOf(delimiter[0] + 1), data.indexOf(delimiter[1]) - 1);
            student.setId(barcode);
            onValidId();
        }
        else if(data.length() == max_length) {
            barcode = data.substring((data.indexOf(delimiter[0]) + 1), data.indexOf(delimiter[1]) - 2);
            student.setId(barcode);
            onValidId();
        }
    }

    private void onValidId() {
        valid = true;
        reason = "";
        submitBtn.setOnClickListener(e -> {
            submitBtn.setEnabled(false);
            student.setReason(reason);
            student.setTimeStamp();
            writeTask = new Write().execute();
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        audioRecord.startRecording();
        task = new MonitorAudioTask();
        task.execute(null, null, null);
    }

    @Override
    public void onPause() {
        super.onPause();
        task.cancel(true);
        audioRecord.stop();
    }

    private void restart() {
        Intent intent = getIntent();
        overridePendingTransition(0, 0);
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        finish();
        overridePendingTransition(0, 0);
        intent.putExtra("Account", account);
        startActivity(intent);
    }

    protected void checkPermissions() {
        final List<String> missingPermissions = new ArrayList<String>();
        for(final String permission : REQUIRED_PERMISSION) {
            final int result = ContextCompat.checkSelfPermission(this, permission);
            if(result != PackageManager.PERMISSION_GRANTED) {
                missingPermissions.add(permission);
            }
        }
        if(!missingPermissions.isEmpty()) {
            final String[] permissions = missingPermissions.toArray(new String[missingPermissions.size()]);
            ActivityCompat.requestPermissions(this, permissions, REQUEST_CODE_ASK_PERM);
        }
        else {
            final int[] grantResults = new int[REQUIRED_PERMISSION.length];
            Arrays.fill(grantResults, PackageManager.PERMISSION_GRANTED);
            onRequestPermissionsResult(REQUEST_CODE_ASK_PERM, REQUIRED_PERMISSION, grantResults);
        }
    }
    @Override
    public void onRequestPermissionsResult(int request, @NonNull String permission[], @NonNull int[] grantResults) {
        switch(request) {
            case REQUEST_CODE_ASK_PERM:
                for(int index = permission.length - 1; index >= 0; --index) {
                    if(grantResults[index] != PackageManager.PERMISSION_GRANTED) {
                        Toast.makeText(this, "Required permission '" + permission[index] + "' not granted, exiting", Toast.LENGTH_LONG).show();
                        finish();
                        return;
                    }
                }
        }
    }

    private class MonitorAudioTask extends AsyncTask<Void, Void, ParseResult> {
        @Override
        protected ParseResult doInBackground(Void... params) {
            final double QUIET_THRESHOLD = 32768.0 * 0.02; //anything higher than 0.02% is considered non-silence
            final double QUIET_WAIT_TIME_SAMPLES = frequency * 0.25; //~0.25 seconds of quiet time before parsing
            short[] buffer = new short[bufferSize];
            Long bufferReadResult = null;
            boolean nonSilence = false;
            ParseResult result = null;

            if(valid) {task.cancel(true);}

            while (!nonSilence) {
                if (isCancelled()) {break;}
                bufferReadResult = new Long(audioRecord.read(buffer, 0, bufferSize));
                if (bufferReadResult > 0) {
                    for (int i = 0; i < bufferReadResult; i++)
                        if (buffer[i] >= QUIET_THRESHOLD) {
                            ByteArrayOutputStream stream = new ByteArrayOutputStream();
                            long silentSamples = 0;

                            //Save this data so far
                            for (int j = i; j < bufferReadResult; j++) {
                                stream.write(buffer[j] & 0xFF);
                                stream.write(buffer[j] >> 8);
                            }

                            //Keep reading until we've reached a certain amount of silence
                            boolean continueLoop = true;
                            while (continueLoop) {
                                bufferReadResult = new Long(audioRecord.read(buffer, 0, bufferSize));
                                if (bufferReadResult < 0)
                                    continueLoop = false;

                                for (int k = 0; k < bufferReadResult; k++) {
                                    stream.write(buffer[k] & 0xFF);
                                    stream.write(buffer[k] >> 8);
                                    if (buffer[k] >= QUIET_THRESHOLD || buffer[k] <= -QUIET_THRESHOLD)
                                        silentSamples = 0;
                                    else
                                        silentSamples++;
                                }

                                if (silentSamples >= QUIET_WAIT_TIME_SAMPLES)
                                    continueLoop = false;
                            }

                            //Convert to array of 16-bit shorts
                            byte[] array = stream.toByteArray();
                            short[] samples = new short[array.length / 2];
                            for (int k = 0; k < samples.length; k++)
                                samples[k] = (short) ((short) (array[k * 2 + 0] & 0xFF) | (short) (array[k * 2 + 1] << 8));

                            //Try parsing the data now!
                            result = CardDataParser.Parse(samples);
                            if (result.errorCode != 0) {
                                //Reverse the array and try again (maybe it was swiped backwards)
                                for (int k = 0; k < samples.length / 2; k++) {
                                    short temp = samples[k];
                                    samples[k] = samples[samples.length - k - 1];
                                    samples[samples.length - k - 1] = temp;
                                }
                                result = CardDataParser.Parse(samples);
                            }

                            nonSilence = true;
                            break;
                        }
                } else
                    break;
            }

            return result;
        }


        protected void onPostExecute(ParseResult result) {
            if (result != null) {
                String str = result.data;

                if (result.errorCode == 0) { } //why is this here maddie¿
                else {
                    String err = Integer.toString(result.errorCode);
                    switch (result.errorCode) {
                        case -1: {
                            err = "NOT_ENOUGH_PEAKS";
                            break;
                        }
                        case -2: {
                            err = "START_SENTINEL_NOT_FOUND";
                            break;
                        }
                        case -3: {
                            err = "PARITY_BIT_CHECK_FAILED";
                            break;
                        }
                        case -4: {
                            err = "LRC_PARITY_BIT_CHECK_FAILED";
                            break;
                        }
                        case -5: {
                            err = "LRC_INVALID";
                            break;
                        }
                        case -6: {
                            err = "NOT_ENOUGH_DATA_FOR_LRC_CHECK";
                            break;
                        }
                    }

                    str += err;
                }
                verifyID(str);
            }
            task = null;
            task = new MonitorAudioTask();

            task.execute(null, null, null);
        }
    }

    private class Write extends AsyncTask<String, Void, Boolean> {

        @Override
        protected Boolean doInBackground(String[] args) {
            try {
                service = new Sheets.Builder(HTTP_TRANSPORT, JSON_FACTORY, credential)
                        .setApplicationName(APPLICATION_NAME)
                        .build();
                ValueRange append = new ValueRange().setValues(Arrays.asList(Arrays.asList(student.getId(), student.getReason(), student.getTimeStamp())));
                AppendValuesResponse aResult = service.spreadsheets().values().append(spreadsheetId, "A1", append)
                        .setValueInputOption("USER_ENTERED").setInsertDataOption("INSERT_ROWS").setIncludeValuesInResponse(true).execute();
            } catch (IOException e) {
            }
            return true;
        }

        protected void onPostExecute(Boolean status) {
            if(status == true) {
                restart();
            }
        }
    }

    private class Login extends AsyncTask<String, Void, String> {

        private Account signIn() {
            GoogleSignInOptions options = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                    .requestScopes(new Scope(SheetsScopes.DRIVE))
                    .requestScopes(new Scope(SheetsScopes.DRIVE_FILE))
                    .requestScopes(new Scope(SheetsScopes.SPREADSHEETS))
                    .build();
            GoogleSignInClient signInClient = GoogleSignIn.getClient(MainActivity.this, options);
            startActivityForResult(signInClient.getSignInIntent(), REQUEST_CODE_SIGN_IN);
            return GoogleSignIn.getLastSignedInAccount(MainActivity.this).getAccount();
        }

        @Override
        protected String doInBackground(String[] params) {

            try {
                HTTP_TRANSPORT = new com.google.api.client.http.javanet.NetHttpTransport();
                signInAccount = GoogleSignIn.getLastSignedInAccount(MainActivity.this);

                if (signInAccount != null)
                    account = signInAccount.getAccount();

                else
                    account = signIn();

                credential = GoogleAccountCredential.usingOAuth2(
                        MainActivity.this,
                        Arrays.asList("https://www.googleapis.com/auth/drive",
                                "https://www.googleapis.com/auth/drive.file",
                                "https://www.googleapis.com/auth/spreadsheets") //give it everything
                );

                credential.setSelectedAccount(account);
                credential.setSelectedAccountName("");

                Sheets service = new Sheets.Builder(HTTP_TRANSPORT, JSON_FACTORY, credential)
                        .setApplicationName(APPLICATION_NAME)
                        .build();
                ValueRange append = new ValueRange().setValues(Arrays.asList(Arrays.asList(student.getId(), student.getReason(), student.getTimeStamp())));
                AppendValuesResponse aResult = service.spreadsheets().values().append(spreadsheetId, "A1", append)
                        .setValueInputOption("USER_ENTERED").setInsertDataOption("INSERT_ROWS").setIncludeValuesInResponse(true).execute();
            } catch (Exception e) {
            }
            return "true";
        }
    }

}
