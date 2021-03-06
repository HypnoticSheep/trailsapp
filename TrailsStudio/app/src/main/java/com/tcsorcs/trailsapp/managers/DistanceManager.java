package com.tcsorcs.trailsapp.managers;

import java.util.ArrayList;
import java.util.TreeMap;

import com.tcsorcs.trailsapp.helpers.Segment;
import com.tcsorcs.trailsapp.helpers.DummyDatabaseHelper; //temporary until database helper is up
import com.tcsorcs.trailsapp.helpers.Location;

/* v1.0 - Full version
 *
 *  Currently FUNCTIONAL but UNTESTED - DummyDatabaseHelper used in place of actual database helper
 *    smarterPathFinder and related helpers not tested thoroughly
 *  Smoke test 1: 2/17/2016 - info passed from DisplayManager -> InputManager -> here but lookup issues occured. Nothing crashed though...
 *  DNE = does not exist
 *  Updated 3/6/2016
 *
 *  POTENTIAL PROBLEM AREAS:
 *  -collection type for path was changed from Stack to a LinkedList - if poll/pop were converted incorrectly, results will be wrong
 *
 *  Quick reference for DB tables referenced (not official names):
 *  -PathTable - stores segments, in order of completion, from start of session
 *  -StatsTable - stores stats (distance, time, pace) for current and other sessions
 *  -RecentScannedTable - stores recent QR codes scanned (including time scanned)
 *
 *  TODO:
 *  -Add stuff to constructor to retrieve stored info in case app closed and reopenedd
 *
 *  TOFIX:
 *  -pathfinder crashing
 */

public class DistanceManager {
    //Global variables - do these need to be stored in the DB?
//	private LinkedList<String> path = new LinkedList<String>(); //path so far, saves the String names of the QR codes
	//private double totalDistance = 0.0; //total distance of the path
	//private long startTimeMillis = -1; //time first location point was scanned

    /***** Setup *****/
	public static DistanceManager getInstance() {
		return DistanceManager.instance;
	}

    /* Need to add stuff to reconstruct current session if app is closed and reopened */
	public static DistanceManager instance = new DistanceManager();

    /***** First contact *****/

    /** Handles new location QR input
     * Builds path if necessary and tells display manager what to display
     *
     * @param locationName String name of location
     */
	public void processQRCodes(String locationName) {
		System.out.println("@DistanceManager.processQRCodes with locationName as "+locationName);//DeBug/if we got here, the temp button callbacks are at least working
		System.out.println("@DistanceManager.processQRCodes location from database " + TrailAppDbManager.getInstance().getDBHelper().getLocation(locationName).getID());//DeBug/are we getting the location? yes

		//I see a QR code - is either first, same as last or not first
        if (DummyDatabaseHelper.getInstance().getPathTableSize() < 1){//first, don't pathfind
            DummyDatabaseHelper.getInstance().addLocationToPath(TrailAppDbManager.getInstance().getDBHelper().getLocation(locationName));
			System.out.println("@DistanceManager.processQRCodes locationName as " + locationName + " is the first location seen");//DeBug
			System.out.println("@DistanceManager.processQRCodes checking first location was added " + DummyDatabaseHelper.getInstance().getLastLocation().getID());//DeBug/check that location was stored
		}
		else if (DummyDatabaseHelper.getInstance().getLastLocation().getID().equals(TrailAppDbManager.getInstance().getDBHelper().getLocation(locationName).getID())){ //if last location seen == location just scanned NOTE: locations returned are not same object, so compare ids
			System.out.println("@DistanceManager.processQRCodes locationName as " + locationName + " is same as previous location");//DeBug
			//do nothing
		}
		else { //not first, need to pathfind
			System.out.println("@DistanceManager.processQRCodes locationName as " + locationName + " is not first");//DeBug
			smarterPathFinder(locationName);
		}
	}


    /***** Get Stats *****/
    /** DisplayManager will poll for stats **/

    /** Returns total distance walked this session
     * Distance calculated each time a distance QR code is scanned
     *
     * @return double, the distance walked
     */
	public double getDistance() {
		return DummyDatabaseHelper.getInstance().getDistance();
	}

    /** Returns time on trails this session
     * Time calculated from current time
     *
     * @return double, the time spent on the trails, in seconds
     */
	public double getTimeOnTrail() {
		long currentTimeMillis = System.currentTimeMillis();
		long totalTimeMillis = currentTimeMillis - DummyDatabaseHelper.getInstance().getStartTime();
		int totalTimeSeconds = (int)(totalTimeMillis/1000);

		return totalTimeSeconds;
	}

    /** Returns pace for this session
     * Pace calculated based on current time but last scanned QR code - so pace will be most
     *      accurate immediately after scan.
     *
     * @return double, the pace in feet/minute
     */
	public double getPace() { return DummyDatabaseHelper.getInstance().getPace();	}


    /** Updates DB distance and pace for this session - guarantees pace will be calculated accurately
     * Pace calculated at each scan and stored as feet/minute
     *
     * @param newDistance, the new distance to place in the StatsTable
     */
    private void updateDistancePace(double newDistance) {
        DummyDatabaseHelper.getInstance().addDistance(newDistance);

        double seconds = getTimeOnTrail();
        double minutes = seconds/60;
        double pace = newDistance / minutes;
        pace = Math.round(pace*1000)/1000;

        DummyDatabaseHelper.getInstance().addPace(pace);
    }


    /***** Pathfinders and helpers *****/
	/*
	 * local class Node
	 * Contains information to help pick shortest subpath
	 */
    /** Local node class
     *  Stores information necessary for pathfinders
     */
	private class Node implements Comparable<Node> {
		String scanName; //QR code name
		Node parent; //parent node, or null
		double nodeDistance; //total distance from start of subpath
		Segment segment; //segment containing this scanName and previous scan name, or null if no previous

		Node (String aScanName, Node aNode, double aDistance, Segment aSegment){
			scanName = aScanName;
			parent = aNode;
			nodeDistance = aDistance;
			segment = aSegment;
		}

		@Override
		public int compareTo(Node left) {
			return Double.compare(this.nodeDistance, left.nodeDistance);
		}
	}


	/*
	 * Finds the shortest path from the most recently scanned code (currentScan) to 
	 *   end of the path travelled already
	 * Assumes:
	 *   -If points have been skipped, will not double back over the last segment in the path 
	 *   	(but may go through other segments already traveled on)
	 * Does: 
	 *   -Handles skipped points
	 * ToDo:
	 *   -Add check side of road before pathfinding
	 *
	 * @param currentScan, String is the location name of the current scan
	 */

	private void smarterPathFinder(String currentScan){
		Location lastScan; //most recent point travelled path
		ArrayList<Segment> attachedSegments; //list of segments, returned from DB
		Segment currentSegment = null; //current segment we're working with
		boolean pointsAdjacent = false; //if two points are on the same segment
        String sideOfRoad; //side of the road the path is - east, west, or cross

		lastScan = DummyDatabaseHelper.getInstance().getLastLocation(); //look at most recent point from PathTable

        sideOfRoad = DummyDatabaseHelper.getInstance().getSideOfRoad(currentScan); //current side of road

		System.out.println("@DistanceManager.smarterPathFinder checking side of road retrieval works: " + DummyDatabaseHelper.getInstance().getSideOfRoad(currentScan));//DeBug//working
		System.out.println("@DistanceManager.smarterPathFinder checking previous scan side of road works: " + DummyDatabaseHelper.getInstance().getSideOfRoad(lastScan));//DeBug//working

		if (!sideOfRoad.equals(DummyDatabaseHelper.getInstance().getSideOfRoad(lastScan))){ //saves side of road or both
            sideOfRoad = "cross";
        }

		System.out.println("@DistanceManager.smarterPathFinder checking sideOfRoad var " + sideOfRoad);//DeBug

		System.out.println("@DistanceManager.smarterPathFinder segments" + TrailAppDbManager.getInstance().getDBHelper().getSegmentsWithPoint(currentScan, null));//DeBug

		//POSSIBLE ERROR LOCATION make sure adding the null for excluded point works - updated to
        //  use TrailAppDbHelper through TrailAppDbManager
		//attachedSegments = DummyDatabaseHelper.getInstance().getSegmentsWithPoint(currentScan, null); //Query DB //segments with currentScan //if nothing found MUST return an empty array list, not null
        attachedSegments = TrailAppDbManager.getInstance().getDBHelper().getSegmentsWithPoint(currentScan, null); //Query DB //segments with currentScan //if nothing found MUST return an empty array list, not null

		System.out.println("@DistanceManager.smarterPathFinder segments" + attachedSegments);//DeBug

        //if something goes wrong and attachedSegments is null or is empty, skip gracefully (redundant if getSegmentsWithPoint succeeds)
		if ((attachedSegments == null) ||
                (attachedSegments.isEmpty())){
			System.err.println("Segment list not created. Pathfinding cannot be done. Returning to processQRCode");
			return;
		}

		//checks if last 2 points scanned are on the same segment
		for (int i = 0; i < attachedSegments.size() && !pointsAdjacent; i++){
			System.out.println("@DistanceManager.smarterPathFinder loc 2");//DeBug
			currentSegment = attachedSegments.get(i);
			pointsAdjacent = currentSegment.segmentHasPoints(lastScan.getID(), currentScan);
		}

		System.out.println("@DistanceManager.smarterPathFinder loc 3");//DeBug

		//if on same segment, we know our next segment, yay!
		if(pointsAdjacent){
			System.out.println("@DistanceManager.smarterPathFinder loc 4");//DeBug
			DummyDatabaseHelper.getInstance().addLocationToPath(TrailAppDbManager.getInstance().getDBHelper().getLocation(currentScan));
			System.out.println("@DistanceManager.smarterPathFinder loc 5");//DeBug
			updateDistancePace(DummyDatabaseHelper.getInstance().getDistance() + currentSegment.getSegmentDistance());//if not initialized, will not be hit
			System.out.println("@DistanceManager.smarterPathFinder current setment is "+currentSegment.getSegmentName());//DeBug
			DisplayManager.getInstance().drawSegment(currentSegment);//display segment
			System.out.println("@DistanceManager.smarterPathFinder loc 6");//DeBug
			return;
		}
		// if points are not adjacent, pathfind

		//And magic happens - 100% chance this will fail first time it's run...
		/*
		 * pathfinder will build unknown path starting with most recent scanned, last point added to
		 * pathfinder list will be last point on main path. We then remove last point from list
		 * and pop it onto path (adding distance as we go) so that last point left in pathfinder list
		 * is most recent point scanned, and now the end of the main path.
		 */
		Node currentNode; //first node we add
		TreeMap<Double, Node> subPath; //keeps track of possible subpaths
		Node shortestNode; //shortest node currently
		Segment subSegment; //temp segment storage
		Node subNode; //temp node storage
		String parentScanName;//name of parent for database query
		Double tempDistance; //temp storage for distance

        currentNode = new Node(currentScan, null, 0.0, null);
		subPath = new TreeMap<Double, Node>();

		subPath.put(0.0, currentNode);//add first

		shortestNode = subPath.get(subPath.firstKey()); //get shortest node

		while(!shortestNode.scanName.equals(lastScan)) { //while the shortest scan is not the lastScan from path
			while (!attachedSegments.isEmpty()) {
				subSegment = attachedSegments.remove(0); //removes and returns, decreasing list //(is this less optimal than using a for loop?)

				/*
				 * If there's further elimination we want to do (ex. don't add segments that cross the road)
				 *   add them here
				 */

                //if on same side of road or road crosses the street, add subSegment to possible path
                if ((sideOfRoad.equals(DummyDatabaseHelper.getInstance().getSideOfRoad(subSegment)) ||
                        (sideOfRoad.equals("cross")))){
                    //DEBUG - if something goes wrong, it's probably here!
                    tempDistance = (subSegment.getSegmentDistance() + shortestNode.nodeDistance);
                    subNode = new Node(subSegment.getOtherPoint(shortestNode.scanName), shortestNode, tempDistance, subSegment);//Segment.secondPoint is the point found attached, not the point given to search for
                    subPath.put(tempDistance, subNode); //add new node to possible path
                }
			}

			//all connected points should be in nodes, so must remove original node from subPath
			subPath.pollFirstEntry(); //removes and returns first in set (shortest path here)

			shortestNode = subPath.get(subPath.firstKey()); //get new shortest

			if (shortestNode.parent == null){
				parentScanName = null;
			} else {
				parentScanName = shortestNode.parent.scanName;
			}
            //updated from DummyDatabaseHelper
			attachedSegments = TrailAppDbManager.getInstance().getDBHelper().getSegmentsWithPoint(shortestNode.scanName, parentScanName);//DNE //Tim
		}//end while

		/*
		 * If we want to let the user change the path they took, add that code here.
		 */

		//we have our path
		addSubPathToPath(shortestNode);
	}

    /** Adds path found by pathfinder to PathTable, in order
     *
     * @param shortestNode, Node contains location name that is end of existing path, node parent
     *                      contains next location name, and so on until most recent scanned
     */
	private void addSubPathToPath(Node shortestNode){
		updateDistancePace(DummyDatabaseHelper.getInstance().getDistance() + shortestNode.nodeDistance); //adds subPath distance to total distance
		Node currentNode = shortestNode; //start w/parent because current is end of main path
		ArrayList<Segment> segmentsList = new ArrayList<Segment>(); //stores for DisplayManager

		while(currentNode.parent != null){
			segmentsList.add(currentNode.segment);

			currentNode = currentNode.parent;
            DummyDatabaseHelper.getInstance().addLocationToPath(TrailAppDbManager.getInstance().getDBHelper().getLocation(currentNode.scanName));
		}

		//display stuff
		DisplayManager.getInstance().drawSegments(segmentsList);//DNE //Dave
	}

    //INCOMPLETE! DO NOT USE!
    /** Extension - if this button is pressed, will find shortest path to a trail entrance
     *
     * CURRENTLY, will base escape route on last QR code scanned, in future, will take actual
     *   GPS location as start point
     *
     *   Does NOT store things in the DB yet
     */
    public void stupidPressButtonToEscape(){
        //get last point scanned
        Location lastScan; //start point of escape route


        lastScan = DummyDatabaseHelper.getInstance().getLastLocation();


        //pathfind until find an entrance

        /*
		 * pathfinder will build unknown path starting lastScan, last point added to
		 * pathfinder list will be entrance.
		 *
		 * Options - (stupid) escape route displays permanently, (smarter) escape route shortens if being taken, erases
		 *     if not taken, (smartest?) escape auto updates until told to stop
		 */
        ArrayList<Segment> attachedSegments; //list of segments, returned from DB //DNE
        Node currentNode; //first node we add
        TreeMap<Double, Node> subPath; //keeps track of possible subpaths
        Node shortestNode; //shortest node currently
        Segment subSegment; //temp segment storage
        Node subNode; //temp node storage
        String parentScanName;//name of parent for database query
        Double tempDistance; //temp storage for distance

        //updated from DummyDatabaseHelper
        attachedSegments = TrailAppDbManager.getInstance().getDBHelper().getSegmentsWithPoint(lastScan.getID(), null); //DNE //Query DB //segments with currentScan //if nothing found MUST return an empty array list, not null

        //if something goes wrong and attachedSegments is null or is empty, skip gracefully (redundant if getSegmentsWithPoint succeeds)
        if ((attachedSegments == null) ||
                (attachedSegments.isEmpty())){
            System.err.println("Segment list not created. Pathfinder failed for escape route. YOU CANNOT ESCAPE!");
            return;
        }

        currentNode = new Node(lastScan.getID(), null, 0.0, null);
        subPath = new TreeMap<Double, Node>();

        subPath.put(0.0, currentNode);//add first

        shortestNode = subPath.get(subPath.firstKey()); //get shortest node

        while(!shortestNode.segment.getSegmentOnEntrance()) { //while segment is not on an entrance
            while (!attachedSegments.isEmpty()) {
                subSegment = attachedSegments.remove(0); //removes and returns, decreasing list //(is this less optimal than using a for loop?)

                //DEBUG - if something goes wrong, it's probably here!
                tempDistance = (subSegment.getSegmentDistance() + shortestNode.nodeDistance);
                subNode = new Node(subSegment.getOtherPoint(shortestNode.scanName), shortestNode, tempDistance, subSegment);//Segment.secondPoint is the point found attached, not the point given to search for
                subPath.put(tempDistance, subNode); //add new node to possible path
            }

            //all connected points should be in nodes, so must remove original node from subPath
            subPath.pollFirstEntry(); //removes and returns first in set (shortest path here)

            shortestNode = subPath.get(subPath.firstKey()); //get new shortest

            if (shortestNode.parent == null){
                parentScanName = null;
            } else {
                parentScanName = shortestNode.parent.scanName;
            }
            //Updated from DummyDatabaseHelper
            attachedSegments = TrailAppDbManager.getInstance().getDBHelper().getSegmentsWithPoint(shortestNode.scanName, parentScanName);//DNE //Tim
        }//end while

        //display

    }
}