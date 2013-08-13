package ch.usi.da.paxos.ring;
/* 
 * Copyright (c) 2013 Università della Svizzera italiana (USI)
 * 
 * This file is part of URingPaxos.
 *
 * URingPaxos is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * URingPaxos is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with URingPaxos.  If not, see <http://www.gnu.org/licenses/>.
 */

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.log4j.Logger;

import ch.usi.da.paxos.api.Learner;
import ch.usi.da.paxos.api.PaxosRole;
import ch.usi.da.paxos.message.Value;
import ch.usi.da.paxos.storage.Decision;

/**
 * Name: MultiLearnerRole<br>
 * Description: <br>
 * 
 * Creation date: Mar 04, 2013<br>
 * $Id$
 * 
 * @author Samuel Benz <benz@geoid.ch>
 */
public class MultiLearnerRole extends Role implements Learner {

	private final static Logger logger = Logger.getLogger(MultiLearnerRole.class);
	
	private final static Logger valuelogger = Logger.getLogger(Value.class);
	
	private final Map<Integer,RingDescription> ringmap = new HashMap<Integer,RingDescription>();
	
	private final List<Integer> ring = new ArrayList<Integer>();
	
	private final int maxRing = 20;
	
	private final BlockingQueue<Decision> values = new LinkedBlockingQueue<Decision>(); 
	
	private final LearnerRole[] learner = new LearnerRole[maxRing];
	
	private int M = 1;
		
	private int deliverRing;

	private final int[] skip_count = new int[maxRing];

	/**
	 * @param rings a list of rings
	 */
	public MultiLearnerRole(List<RingDescription> rings) {
		int minRing = maxRing+1;
		for(RingDescription ring : rings){
			if(ring.getRingID() < minRing){
				minRing = ring.getRingID();
			}
			this.ring.add(ring.getRingID());
			this.ringmap.put(ring.getRingID(),ring);
		}
		Collections.sort(ring);
		RingManager firstRing = rings.get(0).getRingManager();
		deliverRing = minRing;
		logger.debug("MultiRingLearner initial deliverRing=" + deliverRing);
		if(firstRing.getConfiguration().containsKey(ConfigKey.multi_ring_m)){
			M = Integer.parseInt(firstRing.getConfiguration().get(ConfigKey.multi_ring_m));
			logger.debug("MultiRingLearner M=" + M);
		}
	}

	@Override
	public void run() {
		CountDownLatch latch = new CountDownLatch(ringmap.size());
		for(Entry<Integer,RingDescription> e : ringmap.entrySet()){
			// create learners
			RingManager ring = e.getValue().getRingManager();
			Role r = new LearnerRole(ring, latch);
			learner[e.getKey()] = (LearnerRole) r;
			logger.debug("MultiRingLeaner register role: " + PaxosRole.Learner + " at node " + ring.getNodeID() + " in ring " + ring.getRingID());
			ring.registerRole(PaxosRole.Learner);		
			Thread t = new Thread(r);
			t.setName(PaxosRole.Learner + "-" + e.getKey());
			t.start();
			skip_count[e.getKey()] = 0;
		}
		try {
			latch.await(); // wait until all learner are ready
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		int count = 0;
		while(true){
			try{
				if(skip_count[deliverRing] > 0){
					count++;
					skip_count[deliverRing]--;
					valuelogger.debug("Learner " + ringmap.get(deliverRing).getNodeID() + " ring " + deliverRing + " skiped a value (" + skip_count[deliverRing] + " skips left)");
				}else{
					Decision d = learner[deliverRing].getDecisions().take();
					if(d.getValue() != null && d.getValue().getID().equals(Value.skipID) && d.getValue().getValue().length == 4){
						// skip message
						int skip = NetworkManager.byteToInt(d.getValue().getValue());
						skip_count[deliverRing] = skip_count[deliverRing] + skip;
					}else{
						count++;
						// learning an actual proposed value
						values.add(d);
						valuelogger.debug("Learner " + ringmap.get(deliverRing).getNodeID() + " ring " + deliverRing + " " + d);
					}
				}
				if(count >= M){
					count = 0;
					deliverRing = getRingSuccessor(deliverRing);
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;				
			}
		}
	}
	
	private int getRingSuccessor(int id){
		int pos = ring.indexOf(new Integer(id));
		if(pos+1 >= ring.size()){
			return ring.get(0);
		}else{
			return ring.get(pos+1);
		}
	}

	@Override
	public BlockingQueue<Decision> getDecisions() {
		return values;
	}

}