/*
 * Copyright 2018 Google LLC. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.ar.sceneform.samples.hellosceneform;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.support.v4.content.res.TypedArrayUtils;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.widget.Toast;
import com.google.ar.core.Anchor;
import com.google.ar.core.Frame;
import com.google.ar.core.HitResult;
import com.google.ar.core.Plane;
import com.google.ar.core.PointCloud;
import com.google.ar.sceneform.AnchorNode;
import com.google.ar.sceneform.FrameTime;
import com.google.ar.sceneform.Scene;
import com.google.ar.sceneform.math.Vector3;
import com.google.ar.sceneform.rendering.Color;
import com.google.ar.sceneform.rendering.ModelRenderable;
import com.google.ar.sceneform.rendering.Vertex;
import com.google.ar.sceneform.ux.ArFragment;
import com.google.ar.sceneform.ux.TransformableNode;

import com.google.ar.sceneform.samples.hellosceneform.PointCloudNode;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


/**
 * This is an example activity that uses the Sceneform UX package to make common AR tasks easier.
 */
public class HelloSceneformActivity extends AppCompatActivity {
  private static final String TAG = HelloSceneformActivity.class.getSimpleName();
  private static final double MIN_OPENGL_VERSION = 3.0;

  private ArFragment arFragment;
  private ModelRenderable andyRenderable;
  private List<PointCloudNode> pointCloudNodes = new ArrayList<PointCloudNode>();
  private long timestamp;
  private Float max_attention = 0.0f;

    // hold a featurepoint history
  private Map<Integer, List<Float>> allFeaturePoints = new HashMap<>();

    @Override
  @SuppressWarnings({"AndroidApiChecker", "FutureReturnValueIgnored"})
  // CompletableFuture requires api level 24
  // FutureReturnValueIgnored is not valid
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    if (!checkIsSupportedDeviceOrFinish(this)) {
      return;
    }

    setContentView(R.layout.activity_ux);
    arFragment = (ArFragment) getSupportFragmentManager().findFragmentById(R.id.ux_fragment);

    Scene scene = arFragment.getArSceneView().getScene();
    scene.addOnUpdateListener(this::onFrame);

    int scale = 11;
    Color max_color = new Color(android.graphics.Color.CYAN);
    Color min_color = new Color(android.graphics.Color.DKGRAY);
    for (int i =0; i < scale; i++) {
        Color color = LerpRGB(min_color, max_color, i/scale);
        PointCloudNode pointCloudNode = new PointCloudNode(this, color);
        pointCloudNodes.add(pointCloudNode);
        scene.addChild(pointCloudNode);
    }

    // When you build a Renderable, Sceneform loads its resources in the background while returning
    // a CompletableFuture. Call thenAccept(), handle(), or check isDone() before calling get().
    ModelRenderable.builder()
        .setSource(this, R.raw.andy)
        .build()
        .thenAccept(renderable -> andyRenderable = renderable)
        .exceptionally(
            throwable -> {
              Toast toast =
                  Toast.makeText(this, "Unable to load andy renderable", Toast.LENGTH_LONG);
              toast.setGravity(Gravity.CENTER, 0, 0);
              toast.show();
              return null;
            });

    arFragment.setOnTapArPlaneListener(
        (HitResult hitResult, Plane plane, MotionEvent motionEvent) -> {
          if (andyRenderable == null) {
            return;
          }

          // Create the Anchor.
          Anchor anchor = hitResult.createAnchor();
          AnchorNode anchorNode = new AnchorNode(anchor);
          anchorNode.setParent(arFragment.getArSceneView().getScene());

          // Create the transformable andy and add it to the anchor.
          TransformableNode andy = new TransformableNode(arFragment.getTransformationSystem());
          andy.setParent(anchorNode);
          andy.setRenderable(andyRenderable);
          andy.select();
        });
  }

  protected void onFrame(FrameTime frameTime) {
      arFragment.onUpdate(frameTime);
      Frame frame = arFragment.getArSceneView().getArFrame();
      if (frame == null) {
          return;
      }

      PointCloud pointCloud = frame.acquirePointCloud();
      updateAllFeaturePoints(pointCloud);

      pointCloud.release();
  }
  /**
   * Returns false and displays an error message if Sceneform can not run, true if Sceneform can run
   * on this device.
   *
   * <p>Sceneform requires Android N on the device as well as OpenGL 3.0 capabilities.
   *
   * <p>Finishes the activity if Sceneform can not run
   */
  public static boolean checkIsSupportedDeviceOrFinish(final Activity activity) {
    if (Build.VERSION.SDK_INT < VERSION_CODES.N) {
      Log.e(TAG, "Sceneform requires Android N or later");
      Toast.makeText(activity, "Sceneform requires Android N or later", Toast.LENGTH_LONG).show();
      activity.finish();
      return false;
    }
    String openGlVersionString =
        ((ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE))
            .getDeviceConfigurationInfo()
            .getGlEsVersion();
    if (Double.parseDouble(openGlVersionString) < MIN_OPENGL_VERSION) {
      Log.e(TAG, "Sceneform requires OpenGL ES 3.0 later");
      Toast.makeText(activity, "Sceneform requires OpenGL ES 3.0 or later", Toast.LENGTH_LONG)
          .show();
      activity.finish();
      return false;
    }
    return true;
  }

  private void updateAllFeaturePoints(PointCloud cloud) {
      if (this.timestamp != cloud.getTimestamp()) {
          timestamp = cloud.getTimestamp();

          FloatBuffer floatBuffer = cloud.getPoints();
          IntBuffer idBuffer = cloud.getIds();
          // Point clouds are 4 values x,y,z and a confidence value.

          Float confidence = 0.0f;
          Float attention = 0.0f;
          Vector3 feature = new Vector3();

          for (int i = 0; i < floatBuffer.limit() / 4; i++) {
              // feature point
              feature.x = floatBuffer.get(i * 4);
              feature.y = floatBuffer.get(i * 4 + 1);
              feature.z = floatBuffer.get(i * 4 + 2);
              confidence = floatBuffer.get(i * 4 + 3);

              // process each point into global one
              Integer currentId = idBuffer.get(i);
              if (allFeaturePoints.containsKey(currentId)) {
                  // increment attention, alert if xyz and conf is changed
                  updateFeaturePoint(currentId, feature.x, feature.y, feature.z, confidence);
                  //Log.d(TAG, "Point ID "+ currentId+ " attention = " + allFeaturePoints.get(currentId).get(4));
              } else { // initialize this point
                  allFeaturePoints.put(currentId, Arrays.asList(feature.x, feature.y, feature.z, confidence, 0.0f));
              }
              //Log.d(TAG, "Total feature points count = "+ allFeaturePoints.size());
          }
          if (allFeaturePoints.size() != 0)
          {
              splitPointCloudByAttention(allFeaturePoints);
          }


      }


  }
  private void updateFeaturePoint(Integer id, Float x, Float y, Float z, Float conf) {
      List<Float> currentPoint = allFeaturePoints.get(id);
      if (Float.compare(x, currentPoint.get(0)) != 0 ){
          //Log.d(TAG, "Point id = "+ id + ".x is changed from " + currentPoint.get(0) + " to " +x);
          currentPoint.set(0,x);
      }
      if (Float.compare(y, currentPoint.get(1)) != 0) {
          //Log.d(TAG, "Point id = "+ id + ".y is changed from " + currentPoint.get(1) + " to " +y);
          currentPoint.set(1,y);
      }
      if (Float.compare(z, currentPoint.get(2)) != 0) {
          //Log.d(TAG, "Point id = "+ id + ".z is changed from " + currentPoint.get(2) + " to " +z);
          currentPoint.set(2,z);
      }
      if (Float.compare(conf, currentPoint.get(3)) != 0) {
          //Log.d(TAG, "Point id = "+ id + ".c is changed from " + currentPoint.get(3) + " to " +conf);
          currentPoint.set(3,conf);
      }
      // set attention
      Float current_attention = currentPoint.get(4);
      currentPoint.set(4,current_attention +1);
      if (max_attention < current_attention )
          max_attention = current_attention;

      // apply update
      allFeaturePoints.put(id, currentPoint);
  }
  private void splitPointCloudByAttention(Map<Integer, List<Float>> allFeaturePoints) {

      Integer scale = 11;
      // init buffers for different level of attention
      List<List<Float>> ptsByAttentionTier = new ArrayList();
      for (int i = 0; i < scale; i++) {
          ptsByAttentionTier.add(new ArrayList<>());
      }
      for (Map.Entry<Integer, List<Float>> item : allFeaturePoints.entrySet()) {
          Integer key = item.getKey();
          List<Float> value = item.getValue();
          // bukcet the different attention
          Integer tier = 0;
          if(Float.compare(max_attention, 0.0f) != 0) { // avoid divide by 0
              tier = Math.round(value.get(4)/max_attention*10);
          }

          ptsByAttentionTier.get(tier).addAll(Arrays.asList(value.get(0), value.get(1), value.get(2), value.get(3)));
      }
        //convert to float buffer, send buffer to visualizer
      for (int i = 0; i < scale; i++) {
          List<Float> currentTier = ptsByAttentionTier.get(i);
          FloatBuffer floatBuffer = FloatBuffer.allocate(currentTier.size());

          for (Float num : currentTier) {
              floatBuffer.put(num.floatValue());
          }
          pointCloudNodes.get(i).update(floatBuffer);
      }

  }
    public static Color LerpRGB (Color a, Color b, float t)
    {
        return new Color
                (
                        a.r + (b.r - a.r) * t,
                        a.g + (b.g - a.g) * t,
                        a.b + (b.b - a.b) * t,
                        a.a + (b.a - a.a) * t
                );
    }

}
