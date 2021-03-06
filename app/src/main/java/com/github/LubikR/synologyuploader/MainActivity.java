package com.github.LubikR.synologyuploader;

import android.content.ContentResolver;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.github.ma1co.openmemories.framework.DeviceInfo;
import com.github.ma1co.openmemories.framework.ImageInfo;
import com.github.ma1co.openmemories.framework.MediaManager;
import com.sony.scalar.provider.AvindexStore;

import org.apache.http.HttpException;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends WifiActivity {

    private final String TAG = "MainActivity";

    DateFormat formatter = new SimpleDateFormat("ddMMyyyy", Locale.US);

    Button btnSettings;
    Button btnSelectimages;
    Button btnUploadSelected;
    Button btnUpload;
    TextView statusTextView;
    ProgressBar progressBar;

    String ip, port, user, passwd;
    Boolean https, debug;

    private boolean deleteAfterUpload;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnSettings = (Button) findViewById(R.id.Settings);
        btnSelectimages = (Button) findViewById(R.id.btnSelectimages);
        btnUploadSelected = (Button) findViewById(R.id.btnUploadselected);
        btnUpload = (Button) findViewById(R.id.Upload_Now);
        statusTextView = (TextView) findViewById(R.id.textviewStatus);
        progressBar = (ProgressBar) findViewById(R.id.progressBar);

        SharedPreferencesManager.init(getApplicationContext());

        /*
         For testing purpose > delete sharedpreferences
         SharedPreferencesManager.deleteAll();
         */

        btnSettings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                setKeepWifiOn();
                Intent intent = new Intent(MainActivity.this, SettingsActivity.class);
                startActivity(intent);
            }
        });

        btnSelectimages.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                setKeepWifiOn();
                Intent intent = new Intent(MainActivity.this, SelectionActivity.class);
                startActivity(intent);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();

        //ProgressBar Broadcast
        registerReceiver(new Broadcasts(), new IntentFilter("com.github.LubikR.synologyuploader.PROGRESS_BAR_NOTIFICATION"));

        //Check if Connection is already set
        checkIfAlreadySet();

        //News not read, show it
        String versionRead = SharedPreferencesManager.read(getString(R.string.versionReadTag),null);
        if (versionRead == null || !versionRead.equals(getString(R.string.tagLastVersion))) {
            Intent intent = new Intent(MainActivity.this, NewsActivity.class);
            startActivity(intent);
        }
    }

    private void checkIfAlreadySet() {
        if ((SharedPreferencesManager.read(getString(R.string.port), null)) == null) {
            btnUpload.setEnabled(false);
            btnUploadSelected.setEnabled(false);
        } else {
            ip = SharedPreferencesManager.read(getString(R.string.address), null);
            port = SharedPreferencesManager.read(getString(R.string.port), null);
            https = SharedPreferencesManager.readBoolean(getString(R.string.chckBoxUseHttps), false);
            user = SharedPreferencesManager.read(getString(R.string.user), null);
            passwd = SharedPreferencesManager.read(getString(R.string.passwd), null);
            deleteAfterUpload = SharedPreferencesManager.readBoolean(getString(R.string.chckBoxDelete), false);
            debug = SharedPreferencesManager.readBoolean(getString(R.string.chkkBoxLog), false);
            btnUpload.setEnabled(true);
            if (SelectedImages.selectedimages.isEmpty()){
                btnUploadSelected.setEnabled(false);
            }
            else
                btnUploadSelected.setEnabled(true);
        }
    }

    public void uploadSelectedClick(View view) {
        UploadPictures uploadPictures = new UploadPictures();
        uploadPictures.execute(true);
    }


    public void uploadNowClick(View view) {
        UploadPictures uploadPictures = new UploadPictures();
        uploadPictures.execute(false);
    }

    //uploading pictures by Multipart class
    class UploadPictures extends AsyncTask<Boolean, String, Integer> {

        @Override
        protected void onPreExecute() {
            setAutoPowerOffMode(false);
            publishProgress(getString(R.string.connecting));
            btnSettings.setEnabled(false);
            btnUpload.setEnabled(false);
            btnSelectimages.setEnabled(false);
            btnUploadSelected.setEnabled(false);
        }

        @Override
        protected Integer doInBackground(Boolean... selectedOnly) {
            int result = 0;
            int count = 0;
            boolean flaggedForUpload = false;

            MediaManager mediaManager = MediaManager.create(getApplicationContext());
            Cursor cursor = mediaManager.queryImages();

            if (cursor == null) {
                publishProgress(getString(R.string.notSupportedDevice));
                if (debug) {
                    Logger.error(TAG, "Not possible to get images. Probably not supported device");
                }
                result = -1;
            } else if ((count = cursor.getCount()) == 0) {
                publishProgress(getString(R.string.nothingToUpload));
                result = -1;
                if (debug) {
                    Logger.error(TAG, "Nothing to upload");
                }
            } else {
                try {
                    int i = 0;

                    //Get URL
                    if (debug) {
                        Logger.info(TAG, "Getting address : " +
                                ip.replaceAll(".", "*") + ":" + port + " https:" + https); }
                    String address = SynologyAPI.getAddress(ip, port, https);

                    //Check Synology API and retrieve maxVersion
                    JSONObject jsonObject = SynologyAPI.CheckAPIAndRetrieveMaxVersions(address);
                    String maxVersionAuth = ((jsonObject.getJSONObject("data")).getJSONObject("SYNO.API.Auth")).getString("maxVersion");
                    String maxVersionUpload = ((jsonObject.getJSONObject("data")).getJSONObject("SYNO.FileStation.Upload")).getString("maxVersion");
                    if (debug) {
                        Logger.info(TAG, "Got maxVersions: SYNO.API.Auth=" + maxVersionAuth +
                                ", SYNO.FileStation.Upload=" + maxVersionUpload);
                    }

                    // Login to Synology
                    jsonObject = SynologyAPI.login(address, user, passwd, maxVersionAuth);
                    String sid = jsonObject.getJSONObject("data").getString("sid");
                    if (debug) {
                        Logger.info(TAG, "Logged-in OK, sid=" + sid);
                    }

                    //upload images
                    String model = DeviceInfo.getInstance().getModel();
                    String directory = SharedPreferencesManager.read(getString(R.string.settingViewDirectory), null);

                    if(selectedOnly[0]){
                        count = SelectedImages.selectedimages.size();
                    }

                    while (cursor.moveToNext()) {

                        final ImageInfo info = mediaManager.getImageInfo(cursor);
                        String filename = info.getFilename();
                        Date date = info.getDate();
                        Long imageId = info.getImageId();
                        if (SelectedImages.selectedimages.contains(imageId)){
                            flaggedForUpload = true;
                        }


                        if(!selectedOnly[0] || flaggedForUpload) {
                            i++;
                            publishProgress("Uploading " + i + " / " + count);
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    int i = 0;
                                    progressBar.setVisibility(ProgressBar.VISIBLE);
                                    try {
                                        FileChannel fc = ((FileInputStream) info.getFullImage()).getChannel();
                                        i = (int) fc.size();
                                        fc.close();
                                    } catch (IOException e) {
                                        //TODO : Do something with exeption
                                    }
                                    progressBar.setMax(i);
                                }
                            });

                            MultiPartUpload multipart = new MultiPartUpload(address +
                                    SynologyAPI.uploadAPI, "UTF-8", sid);

                            multipart.addFormField("api", "SYNO.FileStation.Upload");
                            multipart.addFormField("version", maxVersionUpload);
                            if (debug) {
                                Logger.info(TAG, "UploadVersion=" + maxVersionUpload);
                            }
                            multipart.addFormField("method", "upload");
                            multipart.addFormField("path", "/" + directory + "/" + model + "/" + formatter.format(date));
                            if (debug) {
                                Logger.info(TAG, "Path=" + "/" + directory + "/" + model + "/" +
                                        formatter.format(date));
                            }
                            multipart.addFormField("create_parents", "true");
                            multipart.addFilePart("file", (FileInputStream) info.getFullImage(), filename, getApplicationContext());
                            if (debug) {
                                Logger.info(TAG, "Filename=" + filename);
                            }

                            String json2 = new String(multipart.finish());
                            String uploadResult = new JSONObject(json2).getString("success");

                            if (uploadResult.equals("true")) {
                                if (deleteAfterUpload) {
                                    // Delete uploaded image
                                    ContentResolver resolver = getApplicationContext().getContentResolver();
                                    Uri uri = mediaManager.getImageContentUri();
                                    long id = mediaManager.getImageId(cursor);
                                    AvindexStore.Images.Media.deleteImage(resolver, uri, id);
                                }
                                if (flaggedForUpload){
                                    flaggedForUpload = false;
                                    SelectedImages.selectedimages.remove(imageId);
                                }
                                if( !selectedOnly[0] && deleteAfterUpload){
                                    SelectedImages.selectedimages.clear();
                                }

                            } else {
                                String errorCode = new JSONObject(json2).getJSONObject("error").getString("code");
                                if (debug) {
                                    Logger.info(TAG, "Error during upload: " + errorCode);
                                }
                                throw new HttpException(errorCode);
                            }


                            //Reset progress bar
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    progressBar.setProgress(0);

                                }

                            });
                        }    
                    }
                    cursor.close();

                    // Do logout
                    SynologyAPI.logout(address, sid, maxVersionAuth);

                } catch (Exception e) {
                    result = -1;
                    if (e instanceof IOException) {
                        publishProgress(getString(R.string.commonError) + e.getMessage());
                        if (debug) {
                            Logger.error(TAG, "IOException - " + e.getMessage());
                        }
                    } else if (e instanceof JSONException) {
                        publishProgress("JSON error - " + e.getMessage());
                        if (debug) {
                            Logger.error(TAG, "JSONException - " + e.getMessage());
                        }
                    } else if (e instanceof HttpException) {
                        publishProgress("Connection error : " + e.getMessage());
                        if (debug) {
                            Logger.error(TAG, "HttpException - " + e.toString());
                        }
                    } else {
                        publishProgress("Something wrong with error : " + e.getMessage());
                        if (debug) {
                            Logger.error(TAG, "AnotherException - " + e.getMessage());
                        }
                    }
                }
            }
            return result;
        }

        @Override
        protected void onProgressUpdate(String... strings) {
            statusTextView.setText(strings[0]);
            if (debug) { Logger.info(TAG, "Publish progress: " + strings[0]); }
        }

        @Override
        protected void onPostExecute(Integer result) {
            setAutoPowerOffMode(true);
            if (result == 0) {
                statusTextView.setText(getString(R.string.uploadOK));
                if (debug) { Logger.info(TAG, "Everything uploaded OK"); }
            }
            btnUpload.setEnabled(true);
            btnSettings.setEnabled(true);
            btnSelectimages.setEnabled(true);
            if (SelectedImages.selectedimages.isEmpty()){
                btnUploadSelected.setEnabled(false);
            }
            else {
                btnUploadSelected.setEnabled(true);
            }

            progressBar.setVisibility(ProgressBar.INVISIBLE);
        }
    }
}
