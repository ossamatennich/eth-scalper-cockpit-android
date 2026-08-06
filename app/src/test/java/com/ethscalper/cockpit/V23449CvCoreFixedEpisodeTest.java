package com.ethscalper.cockpit;
import org.junit.Test;import static org.junit.Assert.*;
public final class V23449CvCoreFixedEpisodeTest {
 @Test public void nonQualifyingBaseObservationCreatesEpisode(){assertNotNull(new CvCoreMovementRegistry().observe("ETHUSDT","LONG",1));}
 @Test public void sameEpisodeAt179999(){CvCoreMovementRegistry r=new CvCoreMovementRegistry();String id=r.observe("ETHUSDT","LONG",1).episodeId;assertEquals(id,r.observe("ETHUSDT","LONG",180000).episodeId);}
 @Test public void newEpisodeAt180001Gap(){CvCoreMovementRegistry r=new CvCoreMovementRegistry();String id=r.observe("ETHUSDT","LONG",1).episodeId;assertNotEquals(id,r.observe("ETHUSDT","LONG",180002).episodeId);}
 @Test public void oppositeSideIndependent(){CvCoreMovementRegistry r=new CvCoreMovementRegistry();assertNotEquals(r.observe("ETHUSDT","LONG",1).episodeId,r.observe("ETHUSDT","SHORT",1).episodeId);}
 @Test public void thresholdChangesCannotChangeEpisode(){CvCoreMovementRegistry r=new CvCoreMovementRegistry();String id=r.observe("ETHUSDT","SHORT",1).episodeId;assertEquals(id,r.observe("ETHUSDT","SHORT",2).episodeId);}
 @Test public void openedEpisodeBlocksEveryRoute(){CvCoreMovementRegistry r=new CvCoreMovementRegistry();CvCoreMovementRegistry.Episode e=r.observe("ETHUSDT","SHORT",1);assertTrue(r.markOpened(e.episodeId,"A"));assertFalse(r.markOpened(e.episodeId,"C"));}
 @Test public void terminalAddsNoCooldown(){CvCoreMovementRegistry r=new CvCoreMovementRegistry();String old=r.observe("ETHUSDT","LONG",1).episodeId;r.markOpened(old,"B");assertNotEquals(old,r.observe("ETHUSDT","LONG",180002).episodeId);}
 @Test public void capacityIsTwoHundred(){CvCoreMovementRegistry r=new CvCoreMovementRegistry();for(int i=0;i<201;i++)r.observe("ETHUSDT",i%2==0?"LONG":"SHORT",1+i*180001L);assertEquals(200,r.size());}
 @Test public void resetCanRestoreActiveEpisode(){CvCoreMovementRegistry r=new CvCoreMovementRegistry();r.restoreOpened("active","ETHUSDT","LONG",1,"B");assertTrue(r.find("active").opened);assertFalse(r.markOpened("active","A"));}
}
