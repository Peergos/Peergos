/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the SIP Communicator community (http://sip-communicator.org).
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.ice4j.ice.harvest;

import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;

import org.ice4j.ice.*;

/**
 * Implements {@link Set} of <tt>CandidateHarvester</tt>s which runs the
 * gathering of candidate addresses performed by its elements in parallel.
 *
 * @author Lyubomir Marinov
 */
public class CandidateHarvesterSet
    extends AbstractSet<CandidateHarvester>
{
    /**
     * The <tt>Logger</tt> used by the <tt>Agent</tt> class and its instances
     * for logging output.
     */
    private static final Logger logger
        = Logger.getLogger(CandidateHarvesterSet.class.getName());

    /**
     * The <tt>CandidateHarvester</tt>s which are the elements of this
     * <tt>Set</tt>.
     */
    private final Collection<CandidateHarvesterSetElement> elements
        = new LinkedList<CandidateHarvesterSetElement>();

    /**
     * A pool of thread used for gathering process.
     */
    private static ExecutorService threadPool = Executors.newCachedThreadPool();

    /**
     * Initializes a new <tt>CandidateHarvesterSet</tt> instance.
     */
    public CandidateHarvesterSet()
    {
    }

    /**
     * Adds a specific <tt>CandidateHarvester</tt> to this
     * <tt>CandidateHarvesterSet</tt> and returns <tt>true</tt> if it is not
     * already present. Otherwise, leaves this set unchanged and returns
     * <tt>false</tt>.
     *
     * @param harvester the <tt>CandidateHarvester</tt> to be added to this
     * <tt>CandidateHarvesterSet</tt>
     * @return <tt>true</tt> if this <tt>CandidateHarvesterSet</tt> did not
     * already contain the specified <tt>harvester</tt>; otherwise,
     * <tt>false</tt>
     * @see Set#add(Object)
     */
    @Override
    public boolean add(CandidateHarvester harvester)
    {
        synchronized (elements)
        {
            for (CandidateHarvesterSetElement element : elements)
                if (element.harvesterEquals(harvester))
                    return false;

            elements.add(new CandidateHarvesterSetElement(harvester));
            return true;
        }
    }

    /**
     * Gathers candidate addresses for a specific <tt>Component</tt>.
     * <tt>CandidateHarvesterSet</tt> delegates to the
     * <tt>CandidateHarvester</tt>s which are its <tt>Set</tt> elements.
     *
     * @param component the <tt>Component</tt> to gather candidate addresses for
     * @see CandidateHarvester#harvest(Component)
     */
    public void harvest(Component component)
    {
        harvest(Arrays.asList(new Component[]{component}), null);
    }


    /**
     * Gathers candidate addresses for a specific <tt>Component</tt>.
     * <tt>CandidateHarvesterSet</tt> delegates to the
     * <tt>CandidateHarvester</tt>s which are its <tt>Set</tt> elements.
     *
     * @param components the <tt>Component</tt> to gather candidate addresses for
     * @see CandidateHarvester#harvest(Component)
     * @param trickleCallback the {@link TrickleCallback} that we will be
     * feeding candidates to, or <tt>null</tt> in case the application doesn't
     * want us trickling any candidates
     */
    public void harvest(final List<Component> components,
                              TrickleCallback trickleCallback)
    {
        synchronized (elements)
        {
            harvest(
                elements.iterator(), components, threadPool, trickleCallback);
        }
    }

    /**
     * Gathers candidate addresses for a specific <tt>Component</tt> using
     * specific <tt>CandidateHarvester</tt>s.
     *
     * @param harvesters the <tt>CandidateHarvester</tt>s to gather candidate
     * addresses for the specified <tt>Component</tt>
     * @param components the <tt>Component</tt>s to gather candidate addresses
     * for.
     * @param executorService the <tt>ExecutorService</tt> to schedule the
     * execution of the gathering of candidate addresses performed by the
     * specified <tt>harvesters</tt>
     * @param trickleCallback the {@link TrickleCallback} that we will be
     * feeding candidates to, or <tt>null</tt> in case the application doesn't
     * want us trickling any candidates
     */
    private void harvest(
            final Iterator<CandidateHarvesterSetElement> harvesters,
            final List<Component>                        components,
                  ExecutorService                        executorService,
            final TrickleCallback                        trickleCallback)
    {
        /*
         * Start asynchronously executing the
         * CandidateHarvester#harvest(Component) method of the harvesters.
         */
        Map<CandidateHarvesterSetTask, Future<?>> tasks
            = new HashMap<CandidateHarvesterSetTask, Future<?>>();

        while (true)
        {
            /*
             * Find the next CandidateHarvester which is to start gathering
             * candidates.
             */
            CandidateHarvesterSetElement harvester;

            synchronized (harvesters)
            {
                if (harvesters.hasNext())
                    harvester = harvesters.next();
                else
                    break;
            }

            if (!harvester.isEnabled())
                continue;

            List<Component> componentsCopy;

            synchronized (components)
            {
                componentsCopy = new ArrayList<Component>(components);
            }

            // Asynchronously start gathering candidates using the harvester.
            CandidateHarvesterSetTask task = new CandidateHarvesterSetTask(
                harvester, componentsCopy, trickleCallback);

            tasks.put(task, executorService.submit(task));
        }

        /*
         * Wait for all harvesters to be given a chance to execute their
         * CandidateHarvester#harvest(Component) method.
         */
        Iterator<Map.Entry<CandidateHarvesterSetTask, Future<?>>> taskIter
            = tasks.entrySet().iterator();

        while (taskIter.hasNext())
        {
            Map.Entry<CandidateHarvesterSetTask, Future<?>> task
                = taskIter.next();
            Future<?> future = task.getValue();

            while (true)
            {
                try
                {
                    future.get();
                    break;
                }
                catch (CancellationException ce)
                {
                    logger.info("harvester cancelled");
                    /*
                     * It got cancelled so we cannot say that the fault is with
                     * its current harvester.
                     */
                    break;
                }
                catch (ExecutionException ee)
                {
                    CandidateHarvesterSetElement harvester
                        = task.getKey().getHarvester();

                    /*
                     * A problem appeared during the execution of the task.
                     * CandidateHarvesterSetTask clears its harvester property
                     * for the purpose of determining whether the problem has
                     * appeared while working with a harvester.
                     */
                    logger.info(
                        "disabling harvester "+ harvester.getHarvester()
                            + " due to ExecutionException: " +
                            ee.getLocalizedMessage());

                    if (harvester != null)
                        harvester.setEnabled(false);
                    break;
                }
                catch (InterruptedException ie)
                {
                    continue;
                }
            }
            taskIter.remove();
        }
    }

    /**
     * Returns an <tt>Iterator</tt> over the <tt>CandidateHarvester</tt>s which
     * are elements in this <tt>CandidateHarvesterSet</tt>. The elements are
     * returned in no particular order.
     *
     * @return an <tt>Iterator</tt> over the <tt>CandidateHarvester</tt>s which
     * are elements in this <tt>CandidateHarvesterSet</tt>
     * @see Set#iterator()
     */
    public Iterator<CandidateHarvester> iterator()
    {
        final Iterator<CandidateHarvesterSetElement> elementIter
            = elements.iterator();

        return
            new Iterator<CandidateHarvester>()
            {
                /**
                 * Determines whether this iteration has more elements.
                 *
                 * @return <tt>true</tt> if this iteration has more elements;
                 * otherwise, <tt>false</tt>
                 * @see Iterator#hasNext()
                 */
                public boolean hasNext()
                {
                    return elementIter.hasNext();
                }

                /**
                 * Returns the next element in this iteration.
                 *
                 * @return the next element in this iteration
                 * @throws NoSuchElementException if this iteration has no more
                 * elements
                 * @see Iterator#next()
                 */
                public CandidateHarvester next()
                    throws NoSuchElementException
                {
                    return elementIter.next().getHarvester();
                }

                /**
                 * Removes from the underlying <tt>CandidateHarvesterSet</tt>
                 * the last <tt>CandidateHarvester</tt> (element) returned by
                 * this <tt>Iterator</tt>. <tt>CandidateHarvestSet</tt> does not
                 * implement the <tt>remove</tt> operation at the time of this
                 * writing i.e. it always throws
                 * <tt>UnsupportedOperationException</tt>.
                 *
                 * @throws IllegalStateException if the <tt>next</tt> method has
                 * not yet been called, or the <tt>remove</tt> method has
                 * already been called after the last call to the <tt>next</tt>
                 * method
                 * @throws UnsupportedOperationException if the <tt>remove</tt>
                 * operation is not supported by this <tt>Iterator</tt>
                 * @see Iterator#remove()
                 */
                public void remove()
                    throws IllegalStateException,
                           UnsupportedOperationException
                {
                    throw new UnsupportedOperationException("remove");
                }
            };
    }

    /**
     * Returns the number of <tt>CandidateHarvester</tt>s which are elements in
     * this <tt>CandidateHarvesterSet</tt>.
     *
     * @return the number of <tt>CandidateHarvester</tt>s which are elements in
     * this <tt>CandidateHarvesterSet</tt>
     * @see Set#size()
     */
    public int size()
    {
        synchronized (elements)
        {
            return elements.size();
        }
    }
}
