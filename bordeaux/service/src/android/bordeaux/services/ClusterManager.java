/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.bordeaux.services;

import android.content.Context;
import android.location.Location;
import android.text.format.Time;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * ClusterManager incrementally indentify representitve clusters from the input location
 * stream. Clusters are updated online using leader based clustering algorithm. The input
 * locations initially are kept by the clusters. Periodially, a cluster consolidating
 * procedure is carried out to refine the cluster centers. After consolidation, the
 * location data are released.
 */
public class ClusterManager {

    private static String TAG = "ClusterManager";

    private static float LOCATION_CLUSTER_RADIUS = 25; // meter

    private static float SEMANTIC_CLUSTER_RADIUS = 50; // meter

    private static long CONSOLIDATE_INTERVAL = 21600000; //

    private static long LOCATION_CLUSTER_THRESHOLD = 180000; // in milliseconds

    private static long SEMANTIC_CLUSTER_THRESHOLD = 1800000; // in milliseconds

    private static String UNKNOWN_LOCATION = "Unknown Location";

    private static String HOME = "Home";

    private static String OFFICE = "Office";

    private Location mLastLocation = null;

    private long mTimeRef = 0;

    private long mSemanticClusterCount = 0;

    private ArrayList<LocationCluster> mLocClusters = new ArrayList<LocationCluster>();

    private ArrayList<SemanticCluster> mSemanticClusters = new ArrayList<SemanticCluster>();

    private AggregatorRecordStorage mStorage;

    private static String SEMANTIC_TABLE = "SemanticTable";

    private static String SEMANTIC_ID = "ID";

    private static String SEMANTIC_LONGITUDE = "Longitude";

    private static String SEMANTIC_LATITUDE = "Latitude";

    private static String[] SEMANTIC_COLUMNS =
            new String[]{ SEMANTIC_ID, SEMANTIC_LONGITUDE, SEMANTIC_LATITUDE};

    public ClusterManager(Context context) {
        mStorage = new AggregatorRecordStorage(context, SEMANTIC_TABLE, SEMANTIC_COLUMNS);

        loadSemanticClusters();
    }

    public void addSample(Location location) {
        float bestClusterDistance = Float.MAX_VALUE;
        int bestClusterIndex = -1;
        long lastDuration;
        long currentTime = location.getTime();

        if (mLastLocation != null) {
            // get the duration spent in the last location
            long duration = location.getTime() - mLastLocation.getTime();
            Log.v(TAG, "sample duration: " + duration +
                  ", number of clusters: " + mLocClusters.size());

            // add the last location to cluster.
            // first find the cluster it belongs to.
            for (int i = 0; i < mLocClusters.size(); ++i) {
                float distance = mLocClusters.get(i).distanceToCenter(mLastLocation);
                Log.v(TAG, "clulster " + i + " is within " + distance + " meters");
                if (distance < bestClusterDistance) {
                    bestClusterDistance = distance;
                    bestClusterIndex = i;
                }
            }

            // add the location to the selected cluster
            if (bestClusterDistance < LOCATION_CLUSTER_RADIUS) {
                Log.v(TAG, "add sample to cluster: " + bestClusterIndex + ",( " +
                    location.getLongitude() + ", " + location.getLatitude() + ")");
                mLocClusters.get(bestClusterIndex).addSample(mLastLocation, duration);
            } else {
                // if it is far away from all existing clusters, create a new cluster.
                LocationCluster cluster =
                    new LocationCluster(mLastLocation, duration, CONSOLIDATE_INTERVAL);
                // move the center of the new cluster if its covering region overlaps
                // with an existing cluster.
                if (bestClusterDistance < 2 * LOCATION_CLUSTER_RADIUS) {
                    Log.e(TAG, "move away activated");
                    cluster.moveAwayCluster(mLocClusters.get(bestClusterIndex),
                            ((float) 2 * LOCATION_CLUSTER_RADIUS));
                }
                mLocClusters.add(cluster);
            }
        } else {
            mTimeRef = currentTime;
        }

        long collectDuration = currentTime - mTimeRef;
        Log.e(TAG, "collect duration: " + collectDuration);
        if (collectDuration > CONSOLIDATE_INTERVAL) {
            // TODO : conslidation takes time. move this to a separate thread later.
            consolidateClusters(collectDuration);
            mTimeRef = currentTime;
        }

        /*
        // TODO: this could be removed
        Log.i(TAG, "location : " +  location.getLongitude() + ", " + location.getLatitude());
        if (mLastLocation != null) {
            Log.i(TAG, "mLastLocation: " +  mLastLocation.getLongitude() + ", " +
                  mLastLocation.getLatitude());
        }  // end of deletion
        */

        mLastLocation = location;
    }

    private void consolidateClusters(long duration) {
        LocationCluster cluster;

        for (int i = mLocClusters.size() - 1; i >= 0; --i) {
            cluster = mLocClusters.get(i);
            cluster.consolidate(duration);

            // TODO: currently set threshold to 1 sec so almost none of the location
            // clusters will be removed.
            if (!cluster.passThreshold(LOCATION_CLUSTER_THRESHOLD)) {
                mLocClusters.remove(cluster);
            }
        }

        // merge clusters whose regions are overlapped. note that after merge
        // cluster center changes but cluster size remains unchanged.
        for (int i = mLocClusters.size() - 1; i >= 0; --i) {
            cluster = mLocClusters.get(i);
            for (int j = i - 1; j >= 0; --j) {
                float distance = mLocClusters.get(j).distanceToCluster(cluster);
                if (distance < LOCATION_CLUSTER_RADIUS) {
                    mLocClusters.get(j).absorbCluster(cluster);
                    mLocClusters.remove(cluster);
                }
            }
        }

        updateSemanticClusters();

        saveSemanticClusters();
    }


    private void loadSemanticClusters() {
        List<Map<String, String> > allData = mStorage.getAllData();

        mSemanticClusters.clear();
        for (Map<String, String> map : allData) {
            String semanticId = map.get(SEMANTIC_ID);
            double longitude = Double.valueOf(map.get(SEMANTIC_LONGITUDE));
            double latitude = Double.valueOf(map.get(SEMANTIC_LATITUDE));

            SemanticCluster cluster = new SemanticCluster(
                    semanticId, longitude, latitude, CONSOLIDATE_INTERVAL);
            mSemanticClusters.add(cluster);
        }

        mSemanticClusterCount = mSemanticClusters.size();
        Log.e(TAG, "load " + mSemanticClusterCount + " semantic clusters.");
    }

    private void saveSemanticClusters() {
        HashMap<String, String> rowFeatures = new HashMap<String, String>();
        Log.e(TAG, "save " + mSemanticClusters.size() + " semantic clusters.");

        mStorage.removeAllData();
        for (SemanticCluster cluster : mSemanticClusters) {
            rowFeatures.clear();
            rowFeatures.put(SEMANTIC_ID, cluster.getSemanticId());

            rowFeatures.put(SEMANTIC_LONGITUDE,
                            String.valueOf(cluster.getCenterLongitude()));
            rowFeatures.put(SEMANTIC_LATITUDE,
                            String.valueOf(cluster.getCenterLatitude()));
            mStorage.addData(rowFeatures);
        }
    }

    private void updateSemanticClusters() {

        HashMap<String, ArrayList<BaseCluster> > semanticMap =
                new HashMap<String, ArrayList<BaseCluster> >();
        for (SemanticCluster cluster : mSemanticClusters) {
            String semanticId = cluster.getSemanticId();
            semanticMap.put(cluster.getSemanticId(), new ArrayList<BaseCluster>());
            semanticMap.get(semanticId).add(cluster);
        }

        // select candidate location clusters
        ArrayList<LocationCluster> candidates = new ArrayList<LocationCluster>();
        for (LocationCluster cluster : mLocClusters) {
            if (cluster.passThreshold(SEMANTIC_CLUSTER_THRESHOLD)) {
                candidates.add(cluster);
            }
        }

        // assign each candidate to a semantic cluster
        for (LocationCluster candidate : candidates) {
            if (candidate.hasSemanticId()) {
                // candidate has been assigned to a semantic cluster
                continue;
            }

            // find the closest semantic cluster
            float bestClusterDistance = Float.MAX_VALUE;
            SemanticCluster bestCluster = null;
            for (SemanticCluster cluster : mSemanticClusters) {
                float distance = cluster.distanceToCluster(candidate);
                Log.e(TAG, "distance to semantic cluster: " + cluster.getSemanticId());

                if (distance < bestClusterDistance) {
                    bestClusterDistance = distance;
                    bestCluster = cluster;
                }
            }

            // add the location to the selected cluster
            SemanticCluster semanticCluster;
            if (bestClusterDistance > SEMANTIC_CLUSTER_RADIUS) {
                // if it is far away from all existing clusters, create a new cluster.
                bestCluster = new SemanticCluster(candidate, CONSOLIDATE_INTERVAL,
                                                  mSemanticClusterCount++);
                String id = bestCluster.getSemanticId();
                semanticMap.put(id, new ArrayList<BaseCluster>());
                semanticMap.get(id).add(bestCluster);
            }
            String semanticId = bestCluster.getSemanticId();
            candidate.setSemanticId(semanticId);
            semanticMap.get(semanticId).add(candidate);
        }
        candidates.clear();
        Log.e(TAG, "number of semantic clusters: " + semanticMap.size());

        // use candidates semantic cluster
        mSemanticClusters.clear();
        for (ArrayList<BaseCluster> clusterList : semanticMap.values()) {
            SemanticCluster semanticCluster = (SemanticCluster) clusterList.get(0);

            Log.e(TAG, "id: " + semanticCluster.getSemanticId() + ", list size: " +
                clusterList.size());

            if (clusterList.size() > 1) {
                // cluster with no new candidate
                semanticCluster.setCluster(clusterList.get(1));
                for (int i = 2; i < clusterList.size(); i++) {
                    semanticCluster.absorbCluster(clusterList.get(i));
                }
            }
            mSemanticClusters.add(semanticCluster);
        }
    }

    public String getSemanticLocation() {
        String label = UNKNOWN_LOCATION;

        if (mLastLocation != null) {
            // TODO: use fast neatest neighbor search speed up location search
            for (SemanticCluster cluster: mSemanticClusters) {
                if (cluster.distanceToCenter(mLastLocation) < SEMANTIC_CLUSTER_RADIUS) {
                    return cluster.getSemanticId();
                }
            }
        }
        return label;
    }
}
