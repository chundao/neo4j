/**
 * Copyright (c) 2002-2011 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package slavetest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.concurrent.CountDownLatch;

import org.junit.Test;
import org.neo4j.com.Protocol;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Transaction;
import org.neo4j.helpers.Pair;
import org.neo4j.kernel.HighlyAvailableGraphDatabase;
import org.neo4j.kernel.ha.AbstractBroker;
import org.neo4j.kernel.ha.Broker;
import org.neo4j.kernel.ha.Master;
import org.neo4j.kernel.ha.MasterClient;
import org.neo4j.kernel.ha.MasterImpl;
import org.neo4j.kernel.ha.zookeeper.Machine;

public class SingleJvmWithNettyTest extends SingleJvmTest
{
    @Test
    public void assertThatNettyIsUsed() throws Exception
    {
        initializeDbs( 1 );
        assertTrue(
                "Slave Broker is not a client",
                ( (HighlyAvailableGraphDatabase) getSlave( 0 ) ).getBroker().getMaster().first() instanceof MasterClient );
    }

    @Override
    protected Broker makeSlaveBroker( MasterImpl master, int masterId, int id, GraphDatabaseService graphDb )
    {
        final Machine masterMachine = new Machine( masterId, -1, 1, -1,
                "localhost:" + Protocol.PORT );
        final Master client = new MasterClient( masterMachine, graphDb );
        return new AbstractBroker( id, graphDb )
        {
            public boolean iAmMaster()
            {
                return false;
            }

            public Pair<Master, Machine> getMasterReally()
            {
                return getMaster();
            }

            public Pair<Master, Machine> getMaster()
            {
                return Pair.of( client, masterMachine );
            }

            public Object instantiateMasterServer( GraphDatabaseService graphDb )
            {
                throw new UnsupportedOperationException(
                        "cannot instantiate master server on slave" );
            }
        };
    }

    @Test
    public void makeSureLogMessagesIsWrittenEvenAfterInternalRestart() throws Exception
    {
        initializeDbs( 1 );
        final CountDownLatch latch1 = new CountDownLatch( 1 );
        final GraphDatabaseService slave = getSlave( 0 );
        Thread t1 = new Thread()
        {
            @Override
            public void run()
            {
                try
                {
                    Transaction tx = slave.beginTx();
                    slave.createNode();
                    latch1.await();
                    tx.success();
                    tx.finish();
                }
                catch ( InterruptedException e )
                {
                    throw new RuntimeException( e );
                }
            }
        };
        t1.start();

        Thread t2 = new Thread()
        {
            @Override
            public void run()
            {
                Transaction tx = slave.beginTx();
                slave.createNode();
                latch1.countDown();
                tx.success();
                tx.finish();
            }
        };
        t2.start();
        
        t1.join();
        t2.join();
        
        assertEquals( 2, countOccurences( "Opened a new channel", new File( dbPath( 1 ), "messages.log" ) ) );
    }

    private int countOccurences( String string, File file ) throws Exception
    {
        BufferedReader reader = new BufferedReader( new FileReader( file ) );
        String line = null;
        int counter = 0;
        while ( (line = reader.readLine()) != null )
        {
            System.out.println( line );
            if ( line.contains( string ) ) counter++;
        }
        reader.close();
        return counter;
    }
}