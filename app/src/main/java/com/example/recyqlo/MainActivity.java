package com.example.recyqlo;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.res.ResourcesCompat;
import androidx.core.content.FileProvider;

import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

import com.google.api.client.extensions.android.http.AndroidHttp;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.vision.v1.Vision;
import com.google.api.services.vision.v1.VisionRequest;
import com.google.api.services.vision.v1.VisionRequestInitializer;
import com.google.api.services.vision.v1.model.AnnotateImageRequest;
import com.google.api.services.vision.v1.model.BatchAnnotateImagesRequest;
import com.google.api.services.vision.v1.model.BatchAnnotateImagesResponse;
import com.google.api.services.vision.v1.model.EntityAnnotation;
import com.google.api.services.vision.v1.model.Feature;
import com.google.api.services.vision.v1.model.Image;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

import timber.log.Timber;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = MainActivity.class.getSimpleName();
    private static final int REQUEST_TAKE_PHOTO = 1;
    private static final int MAX_DIMENSION = 1200;

    private static final String CLOUD_VISION_API_KEY = BuildConfig.API_KEY;
    private static final String ANDROID_CERT_HEADER = "X-Android-Cert";
    private static final String ANDROID_PACKAGE_HEADER = "X-Android-Package";
    private static final int MAX_LABEL_RESULTS = 10;

    private Button camera_open_id;
    private ImageView click_image_id;
    private String currentPhotoPath;
    private TextView imageDetail;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (BuildConfig.DEBUG) {
            Timber.plant(new Timber.DebugTree());
        }

        imageDetail = findViewById(R.id.image_details);
        imageDetail.setText("Take a photo of an object to find out how to recycle it!");

        camera_open_id = findViewById(R.id.camera_button);
        click_image_id = findViewById(R.id.click_image);

        camera_open_id.setOnClickListener(new View.OnClickListener() {

            @Override
            public void onClick(View v){
                dispatchTakePictureIntent();
            }
        });
    }

    protected void onActivityResult (int requestCode,
                                     int resultCode,
                                     Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if(requestCode == REQUEST_TAKE_PHOTO) {
            File photoFile = new File(currentPhotoPath);
            Bitmap bitmap = BitmapFactory.decodeFile(photoFile.getAbsolutePath());
            click_image_id.setImageBitmap(bitmap);

            try {
                Bitmap scaledBitmap = scaleBitmapDown(bitmap, MAX_DIMENSION);
                callCloudVision(scaledBitmap);
            } catch (Exception e) {
                Timber.e(e);
            }
        }
    }

    private void dispatchTakePictureIntent() {
        Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        // Ensure that there's a camera activity to handle the intent
        if (takePictureIntent.resolveActivity(getPackageManager()) != null) {
            // Create the File where the photo should go
            File photoFile = null;
            try {
                photoFile = createImageFile();
            } catch (IOException ex) {
                // Error occurred while creating the File
                System.out.println("Warning: Error occurred while creating photo file");
            }
            // Continue only if the File was successfully created
            if (photoFile != null) {
                Uri photoURI = FileProvider.getUriForFile(this,
                        "com.example.android.fileprovider",
                        photoFile);
                takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoURI);
                startActivityForResult(takePictureIntent, REQUEST_TAKE_PHOTO);
            }
        }
    }

    private File createImageFile() throws IOException {
        if (BuildConfig.DEBUG) {
            Timber.plant(new Timber.DebugTree());
        }

        // Create an image file name
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String imageFileName = "JPEG_" + timeStamp + "_";
        File storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        File image = File.createTempFile(
                imageFileName,  /* prefix */
                ".jpg",         /* suffix */
                storageDir      /* directory */
        );

        // Save a file: path for use with ACTION_VIEW intents
        currentPhotoPath = image.getAbsolutePath();
        return image;
    }

    private Vision.Images.Annotate prepareAnnotationRequest(Bitmap bitmap) throws IOException {
        HttpTransport httpTransport = AndroidHttp.newCompatibleTransport();
        JsonFactory jsonFactory = GsonFactory.getDefaultInstance();

        VisionRequestInitializer requestInitializer =
                new VisionRequestInitializer(CLOUD_VISION_API_KEY) {
                    /**
                     * We override this so we can inject important identifying fields into the HTTP
                     * headers. This enables use of a restricted cloud platform API key.
                     */
                    @Override
                    protected void initializeVisionRequest(VisionRequest<?> visionRequest)
                            throws IOException {
                        super.initializeVisionRequest(visionRequest);

                        String packageName = getPackageName();
                        visionRequest.getRequestHeaders().set(ANDROID_PACKAGE_HEADER, packageName);

                        String sig = PackageManagerUtils.getSignature(getPackageManager(), packageName);

                        visionRequest.getRequestHeaders().set(ANDROID_CERT_HEADER, sig);
                    }
                };

        Vision.Builder builder = new Vision.Builder(httpTransport, jsonFactory, null);
        builder.setVisionRequestInitializer(requestInitializer);

        Vision vision = builder.build();

        BatchAnnotateImagesRequest batchAnnotateImagesRequest =
                new BatchAnnotateImagesRequest();
        batchAnnotateImagesRequest.setRequests(new ArrayList<AnnotateImageRequest>() {{
            AnnotateImageRequest annotateImageRequest = new AnnotateImageRequest();

            // Add the image
            Image base64EncodedImage = new Image();
            // Convert the bitmap to a JPEG
            // Just in case it's a format that Android understands but Cloud Vision
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, byteArrayOutputStream);
            byte[] imageBytes = byteArrayOutputStream.toByteArray();

            // Base64 encode the JPEG
            base64EncodedImage.encodeContent(imageBytes);
            annotateImageRequest.setImage(base64EncodedImage);

            // add the features we want
            annotateImageRequest.setFeatures(new ArrayList<Feature>() {{
                Feature labelDetection = new Feature();
                labelDetection.setType("LABEL_DETECTION");
                labelDetection.setMaxResults(MAX_LABEL_RESULTS);
                add(labelDetection);
            }});

            // Add the list of one thing to the request
            add(annotateImageRequest);
        }});

        Vision.Images.Annotate annotateRequest =
                vision.images().annotate(batchAnnotateImagesRequest);
        // Due to a bug: requests to Vision API containing large images fail when GZipped.
        annotateRequest.setDisableGZipContent(true);
        Log.d(TAG, "created Cloud Vision request object, sending request");

        return annotateRequest;
    }

    private class LableDetectionTask extends AsyncTask<Object, Void, String> {
        private final WeakReference<MainActivity> mActivityWeakReference;
        private Vision.Images.Annotate mRequest;

        LableDetectionTask(MainActivity activity, Vision.Images.Annotate annotate) {
            mActivityWeakReference = new WeakReference<>(activity);
            mRequest = annotate;
        }

        @Override
        protected String doInBackground(Object... params) {
            try {
                Timber.d("created Cloud Vision request object, sending request");
                BatchAnnotateImagesResponse response = mRequest.execute();
                return findRelevantLabel(response);

            } catch (GoogleJsonResponseException e) {
                Timber.d("failed to make API request because %s", e.getContent());
            } catch (IOException e) {
                Timber.d("failed to make API request because of other IOException %s", e.getMessage());
            }
            return "Cloud Vision API request failed. Check logs for details.";
        }

        protected void onPostExecute(String result) {
            MainActivity activity = mActivityWeakReference.get();
            if (activity != null && !activity.isFinishing()) {

                //TODO: this should make the info relevant to resulting label to pop up
                waste_info(result);
                TextView imageDetail = activity.findViewById(R.id.image_details);
                imageDetail.setText(result.substring(0, 1).toUpperCase() + result.substring(1));
            }
        }
    }

    private Bitmap scaleBitmapDown(Bitmap bitmap, int maxDimension) {
        int originalWidth = bitmap.getWidth();
        int originalHeight = bitmap.getHeight();
        int resizedWidth = maxDimension;
        int resizedHeight = maxDimension;

        if (originalHeight > originalWidth) {
            resizedHeight = maxDimension;
            resizedWidth = (int) (resizedHeight * (float) originalWidth / (float) originalHeight);
        } else if (originalWidth > originalHeight) {
            resizedWidth = maxDimension;
            resizedHeight = (int) (resizedWidth * (float) originalHeight / (float) originalWidth);
        } else if (originalHeight == originalWidth) {
            resizedHeight = maxDimension;
            resizedWidth = maxDimension;
        }
        return Bitmap.createScaledBitmap(bitmap, resizedWidth, resizedHeight, false);
    }

    private void callCloudVision(final Bitmap bitmap) {
        imageDetail.setText("Identifying object...");

        // Do the real work in an async task, because we need to use the network anyway
        try {
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG,70, byteArrayOutputStream);
            AsyncTask<Object, Void, String> labelDetectionTask = new LableDetectionTask(this, prepareAnnotationRequest(bitmap));
            labelDetectionTask.execute();
        } catch (IOException e) {
            Timber.d("failed to make API request because of other IOException %s", e.getMessage());
        }
    }

    private static String findRelevantLabel(BatchAnnotateImagesResponse response) {
        ArrayList<String> labelList = convertResponseToList(response);
        return chooseLabel(labelList);
    }

    private static ArrayList<String> convertResponseToList(BatchAnnotateImagesResponse response) {
        ArrayList labelList = new ArrayList();

        List<EntityAnnotation> labels = response.getResponses().get(0).getLabelAnnotations();
        if (labels != null) {
            for (EntityAnnotation label : labels) {
                labelList.add(label.getDescription());
            }
        }

        System.out.println(labelList.toString());
        return labelList;
    }

    private static String chooseLabel(ArrayList<String> labels){
        List<String> labelsOfInterest = new ArrayList<String>();
        labelsOfInterest.add("Aluminum can");
        labelsOfInterest.add("Plastic bottle");
        labelsOfInterest.add("Cardboard");
        labelsOfInterest.add("Paper");
        labelsOfInterest.add("Floor");
        labelsOfInterest.add("Computer");
        labelsOfInterest.add("Laptop");
        labelsOfInterest.add("Computer");
        labelsOfInterest.add("Laptop");
        labelsOfInterest.add("Tin can");

        for(String found : labels) {
            for(String label : labelsOfInterest) {
                if (label.equals(found)) return label.toLowerCase();
            }
        }

        return "no relevant label";
    }

    private void waste_info(String item) {
        AlertDialog.Builder popUp = new AlertDialog.Builder(this);
        popUp.setTitle(item.substring(0, 1).toUpperCase() + item.substring(1));
        switch (item) {
            case "plastic bottle":
                popUp.setMessage("How to recycle: Empty and rinse the bottle, squash the bottle, leave on the labels and replace the lids, then recycle in plastic waste \n\nFun Fact: One 500ml plastic bottle has a total carbon footprint equal to 82.8grams of carbon dioxide");
                break;
            case "aluminum can":
                popUp.setMessage("How to recycle: Empty and rinse can, squash can and recycle me \n\nFun Fact: One 330ml aluminium can has a total carbon footprint equal to 170grams of carbon dioxide");
                break;
            case "tin can":
                popUp.setMessage("How to recycle: Empty and rinse can, squash can and recycle me \n\nFun Fact: One 330ml aluminium can has a total carbon footprint equal to 170grams of carbon dioxide");
                break;
            case "paper":
                popUp.setMessage("How to recycle: Remove any staples or tape and throw me in the paper waste \n\nFun Fact: 500 sheets of paper has a total carbon footprint equal to 4.59lbs of carbon dioxide");
                break;
            case "laptop":
                popUp.setMessage("How to recycle: take me to a household waste recycling center, remember to delete and remove all data from the hard-drive, you can recycle the laptop battery at household battery collection points\n\nFun Fact: 15 PCs can generate as much carbon as a typical midsize car");
                break;
            case "glass bottle":
                popUp.setMessage("How to recycle: empty and rinse me, put the lid back on and throw me in glass waste\n\nFun Fact: Every tonne of recycled glass saves 670kilograms of carbon dioxide emissions");
                break;
            default:
                popUp.setMessage("Sorry this item was not recognised in our database!");
        }
        popUp.setCancelable(true);

        popUp.setPositiveButton("Okay!",
                new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        dialogInterface.cancel();
                    }
                });
        popUp.show();
    }
}
