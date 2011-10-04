/*
Copyright � 2008 Brent Boyer

This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the Lesser GNU General Public License for more details.

You should have received a copy of the Lesser GNU General Public License along with this program (see the license directory in this project).  If not, see <http://www.gnu.org/licenses/>.
*/

/*
Programmer notes:

--after working with this class, I am a bit unhappy when you use it inside a main method:
	--the resulting code can be a little too wordy:
		--its annoying to have to see all the Callable/Runnable packaging code
		--there can be many indentations and curly braces
	--DO ALL THESE CONCERNS GO AWAY ONCE CAN USE THE NEW JAVA CLOSURES (now due in jdk 8...)?
		http://cr.openjdk.java.net/~briangoetz/lambda/lambda-state-2.html
	--until we get closures, I have spent some time thinking about alternative ways to implement/use this class.
	I think that the best approach is to intercept on the command line code that want wrapped with this class's functionality.
		--usage would look like:
			java Exec some.classname <normal args>
			java ExecThenExit some.classname <normal args>
			java ExecOnEdt some.classname <normal args>
		--how it works:
			--need to write 3 new classes (above used the default package), each of which will:
				--create a Class for the fully qualified class name (some.classname above)
				--create a Runnable which invokes its main method with the remaining command line args (<normal args> above)
				--call the appropriate method of this Execute class
		--pros:
			--could trivially add/remove Execute's functionality for any class with no programming, just doing script or IDE configuration
		--cons:
			--only works with main (or, would need to add support for specifying some other method)
			--Execute's functionality, not being written into the class, might be all too easy to be overlooked
*/

package bb.util;

import java.awt.EventQueue;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.swing.JOptionPane;

/**
* Executes a "task",
* defined here as arbitrary Java code that is contained inside either a {@link Callable} or {@link Runnable} instance.
* <p>
* The following always occurs:
* <ol>
*  <li>
*		assigns a new {@link UncaughtThrowableLogger} to {@link Thread#setDefaultUncaughtExceptionHandler Thread},
*		which ensures that every uncaught Throwable is properly logged.
*		Note that this assignment will remain for the life of the JVM, unless subsequently changed by other code.
*  </li>
*  <li>logs the task's start and stop dates, as well as the total execution time</li>
*  <li>ensures that any Throwable raised by the task is either logged or rethrown as a RuntimeException</li>
* </ol>
* Additionally, the following may/may not occur, depending on what method of this class is called (see each method's javadocs for details):
* <ol>
*  <li>
*		executes the task on {@link EventQueue}'s {@link EventQueue#isDispatchThread dispatch thread}
*		instead of the calling thread (this is required by Java GUI components)
*  </li>
*  <li>plays an appropriate sound, depending on whether task finishes normally or abnormally</li>
*  <li>forces the JVM to exit with an appropriate exit code, depending on whether task finishes normally or abnormally</li>
* </ol>
* <p>
* This functionality is exposed via static methods like
* {@link #thenContinue(Callable) thenContinue}, {@link #thenExitIfEntryPoint(Callable) thenExitIfEntryPoint}, {@link #usingEdt(Callable) usingEdt}.
* <p>
* The original motivation of this class was to simplify and standardize the implementation of <code>main</code> methods,
* since they often require the same boilerplate code (the functionality described above).
* One way to write a (non-GUI) <code>main</code> method using this class is:
* <pre><code>
	&#47;**
	* ...
	* <p>
	* If this method is this Java process's entry point (i.e. first <code>main</code> method),
	* then its final action is a call to {@link System#exit System.exit}, which means that <i>this method never returns</i>;
	* its exit code is 0 if it executes normally, 1 if it throws a Throwable (which will be caught and logged).
	* Otherwise, this method returns and leaves the JVM running.
	*&#47;
	public static void main(final String[] args) {
		Execute.thenExitIfEntryPoint( new Callable<Void>() { public Void call() throws Exception {
			// insert here whatever specific code your main is supposed to do
			return null;
		} } );
	}
* </code></pre>
* If the purpose is to launch a GUI, then this form almost certainly should be used:
* <pre><code>
	&#47;**
	* Creates a GUI and then returns.
	*&#47;
	public static void main(final String[] args) {
		Execute.usingEdt( new Runnable() { public void run() {
			// insert here whatever specific code your main is supposed to do
		} } );
	}
* </code></pre>
* <p>
* Concerning the execution time that is always logged by this class:
* it can serve as a quick and dirty benchmark only if
* a) task runs long enough that a single execution yields an accurate execution time
* and b) you do not care about execution statistics (i.e. means, standard deviations, confidence intervals).
* For all serious benchmarking needs, use {@link Benchmark}.
* Here is an example of how some quick benchmarks may be obtained:
* <pre><code>
	&#47;**
	* Benchmarks a series of tasks; see the logs (files and/or console) for results.
	*&#47;
	public static void main(final String[] args) {
		Execute.thenContinue( new Runnable() { public void run() {
			// insert here code for task #1
		} } );
		
		Execute.thenContinue( new Runnable() { public void run() {
			// insert here code for task #2
		} } );
		
		// etc...
	}
* </code></pre>
* <p>
* This class is multithread safe: its public api is static methods which use no shared state.
* (Its instance state is completely encapsulated, and is mostly immutable or thread safe).
* <p>
* @author Brent Boyer
*/
public class Execute {
	
	// -------------------- instance fields --------------------
	
	private final String classCalling;
	private final String methodCalling;
	private final Object task;
	private final Level levelEvents;
	private final boolean soundOnTaskSuccess;
	private final boolean exitWhenDone;
	private final Logger logger;
	
	// -------------------- public api, part 2: thenExitIfEntryPoint --------------------
	
	/** Returns <code>{@link #thenExitIfEntryPoint(Callable, Logger) thenExitIfEntryPoint}(task, {@link LogUtil#getLogger2})</code>. */
	public static <T> T thenExitIfEntryPoint(Callable<T> task) throws IllegalArgumentException, RuntimeException {
		return thenExitIfEntryPoint(task, Logger.getAnonymousLogger());
	}
	
	/**
	* Executes task and plays a distinct sound depending on if task returned normally or threw a Throwable.
	* If the class calling this method is the current Java process's entry point (i.e. first <code>main</code> method)
	* then logs task's result (or any Throwable it threw)
	* and calls {@link System#exit System.exit} with an appropriate exit code (0 if task returned normally, 1 if it threw a Throwable),
	* which means that <i>this method never returns</i>.
	* Otherwise, this method returns task's result (or rethrows any Throwable it threw as a RuntimeException) and leaves the JVM running.
	* All normal events (e.g. task's start/stop dates and execution time) are logged at {@link Level#INFO}
	* (Throwables are always logged at {@link Level#SEVERE}).
	* <p>
	* Altho many Java style guides
	* <a href="http://cwe.mitre.org/data/definitions/382.html">warn</a>
	* <a href="http://stackoverflow.com/questions/309396/java-how-to-test-methods-that-call-system-exit">against</a>
	* programs explicitly forcing JVM exit,
	* this behavior is necessary if the program that <code>main</code> belongs to
	* is actually is part of a sequence of programs that rely on exit codes to stop processing in the event of failure.
	* <p>
	* @throws IllegalArgumentException if the {@link #Execute constructor} objects to one of this method's params, or some other method on the call stack throws this
	* @throws RuntimeException (or some subclass) if any other Throwable is thrown while executing task; this may merely wrap the underlying Throwable
	*/
	@SuppressWarnings("unchecked")
	public static <T> T thenExitIfEntryPoint(Callable<T> task, Logger logger) throws IllegalArgumentException, RuntimeException {
		return (T) thenExitIfEntryPointImpl(task, logger);
	}
	
	/** Simply calls <code>{@link #thenExitIfEntryPoint(Runnable, Logger) thenExitIfEntryPoint}(task, {@link LogUtil#getLogger2})</code>. */
	public static void thenExitIfEntryPoint(Runnable task) throws IllegalArgumentException, RuntimeException {
		thenExitIfEntryPoint(task, Logger.getAnonymousLogger());
	}
	
	/** Same as <code>{@link #thenExitIfEntryPoint(Callable, Logger) thenExitIfEntryPoint}</code>, except that task is a Runnable, and so has no result. */
	public static void thenExitIfEntryPoint(Runnable task, Logger logger) throws IllegalArgumentException, RuntimeException {
		thenExitIfEntryPointImpl(task, logger);
	}
	
	private static Object thenExitIfEntryPointImpl(Object task, Logger logger) throws IllegalArgumentException, RuntimeException {
		Caller caller = new Caller();
		String classCalling = caller.className;
		String methodCalling = caller.methodName;
		Level levelEvents = Level.INFO;
		boolean soundOnTaskSuccess = true;
		boolean exitWhenDone = caller.isProgramEntryPoint;
		Execute execute = new Execute(classCalling, methodCalling, task, levelEvents, soundOnTaskSuccess, exitWhenDone, logger);
		return execute.perform();
	}
	
	// -------------------- constructor --------------------
	
	/**
	* Constructor.
	* <p>
	* @param classCalling name of the class which is calling Execute; this is the class name that will be used in the log records
	* @param methodCalling name of the method which is calling Execute; this is the method name that will be used in the log records
	* @param task wraps arbitrary code to be executed
	* @param levelEvents the logging Level to use for normal (non-Throwable) events
	* @param soundOnTaskSuccess if true, causes a sound to be played if task completes normally
	* (a sound is always played if task throws a Throwable)
	* @param exitWhenDone if true, then
	* a) ensures that {@link System#exit System.exit} is called at the end of this method
	* (with an exit code of 0 if task.call returned normally, and an exit code of 1 if task.call threw some Throwable),
	* which means that this method never actually returns to the caller,
	* b) a sound is played when task returns normally (a sound is always played if task returns abnormally),
	* and c) task's return value or Throwable thrown are logged;
	* if false, then control should return to the caller (unless task or some other code calls System.exit)
	* @param logger used to log all events to (start/stop dates and execution times, uncaught Throwables, etc)
	* @throws IllegalArgumentException if if classCalling or methodCalling is {@link Check#notBlank blank};
	* task is null; task is not an instanceof Callable or Runnable;
	* levelEvents is null; logger is null
	*/
	private Execute(String classCalling, String methodCalling, Object task, Level levelEvents, boolean soundOnTaskSuccess, boolean exitWhenDone, Logger logger) throws IllegalArgumentException {
		Check.arg().notBlank(classCalling);
		Check.arg().notBlank(methodCalling);
		Check.arg().notNull(task);
		if (!(task instanceof Callable) && !(task instanceof Runnable)) throw new IllegalArgumentException("task's type = " + task.getClass().getName() + " is unsupported");
		Check.arg().notNull(levelEvents);
		Check.arg().notNull(logger);
		
		this.classCalling = classCalling;
		this.methodCalling = methodCalling;
		this.task = task;
		this.levelEvents = levelEvents;
		this.soundOnTaskSuccess = soundOnTaskSuccess;
		this.exitWhenDone = exitWhenDone;
		this.logger = logger;
	}
	
	// -------------------- perform --------------------
	
	/**
	* Fundamental API method that implements the essential functionality of this class.
	* <p>
	* @return the result of {@link #task}.call
	* @throws RuntimeException (or some subclass) if task.call throws it; this may wrap the underlying Throwable
	*/
	private Object perform() throws RuntimeException {
		long t1 = System.nanoTime();
		int exitCode = 0;
		try {
			ensureUncaughtThrowablesHandled();
			logStart();
			return executeTask();
		}
		catch (Throwable t) {
			exitCode = 1;
			if (exitWhenDone) logProblem(t);	// must log it here, since JVM will exit in the finally and so it will never be seen otherwise
			throw ThrowableUtil.toRuntimeException(t);
		}
		finally {
			logStop(t1);	// CRITICAL: do this first, before play sound etc below, since if am using this class for crude benchmarks, do not want their time included
			seeIfShouldExit(exitCode);
		}
	}
	
	// -------------------- ensureUncaughtThrowablesHandled --------------------
	
	private void ensureUncaughtThrowablesHandled() {
		Thread.setDefaultUncaughtExceptionHandler( new UncaughtThrowableLogger(logger) );
	}
	
	// -------------------- logXXX --------------------
	
	private void logStart() {
		log(levelEvents, "task start date = " + getTimeStamp(), null);
	}
	
	private static String getTimeStamp() {
	    return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ").format(new Date());
	}
	
	/** Contract: should never throw any Throwable, since will be used in a finally clause. */
	private void logStop(long t1) {
		try {
			log(levelEvents, "task stop date = " + getTimeStamp(), null);
			
			long t2 = System.nanoTime();
			log(levelEvents, "task execution time = " + ((t2 - t1) * 1e-9) + " seconds", null);
		}
		catch (Throwable t) {
			logProblem(t);
		}
	}
	
	/** Contract: should never throw any Throwable, since may be implicitly used in a finally clause. */
	private void logProblem(Throwable t) {
		try {
			log(Level.SEVERE, "UNEXPECTED Throwable caught", t);
		}
		catch (Throwable t2) {
			try {
				System.err.println();
				System.err.println("THE FOLLOWING THROWABLE WAS RAISED:");
				t.printStackTrace(System.err);
				System.err.println("BUT THE ABOVE THROWABLE FAILED TO BE LOGGED DUE TO:");
				t2.printStackTrace(System.err);
			}
			catch (Throwable t3) {
				// have to eat it at this point, as there is nothing else that can do
			}
		}
	}
	
	private void log(Level level, String msg, Throwable t) {
		logger.logp(level, classCalling, methodCalling, msg, t);
	}
	
	// -------------------- executeTask --------------------
	
	@SuppressWarnings("unchecked")
	private Object executeTask() throws Exception {
		Object result = null;
		if (task instanceof Callable) {
			result = ((Callable) task).call();
			if (exitWhenDone) log(levelEvents, "task's result = " + result, null);	// must log it here, since JVM will exit in perform's finally and so it will never be seen otherwise
		}
		else if (task instanceof Runnable) {
			((Runnable) task).run();
		}
		else {
			throw new IllegalStateException("task's type = " + task.getClass().getName() + " is unsupported");
		}
		
		return result;
	}
	
	// -------------------- seeIfShouldExit --------------------
	
	/** Contract: should never throw any Throwable, since will be used in a finally clause. */
	private void seeIfShouldExit(int exitCode) {
		try {
			if (exitWhenDone) {
				log(levelEvents, "will next call System.exit(" + exitCode + ")", null);
				//LogUtil.close(logger);	// do NOT do anything like this: logger could still be needed the ctach below or by some other thread (e.g. a shutdown hook triggered by exit below); the constant flushing of logger done by this class should suffice
				System.exit(exitCode);
			}
		}
		catch (Throwable t) {
			logProblem(t);
		}
	}
	
	// -------------------- Caller (static inner class) --------------------
	
	/**
	* Records information about the class that is calling Execute:
	* its class name, method name, and whether or not it is the actual program entry point of the current Java process.
	* <p>
	* This class is multithread safe: it is immutable.
	*/
	private static class Caller {
		
		private static final String classNameThread = Thread.class.getName();
		private static final String classNameCaller = Caller.class.getName();
		private static final String classNameExecute = Execute.class.getName();
		
		private final String className;
		private final String methodName;
		private final boolean isProgramEntryPoint;
		
		/**
		* First acquires the stack trace of the current thread.
		* Then confirms that the 0th element comes from {@link Thread#getStackTrace Thread.getStackTrace}.
		* Next starts searching the remaining elements.
		* The first {@link StackTraceElement} that it comes across whose class name does not equal this class's or <code>Execute</code>'s
		* is the class/method that is calling <code>Execute</code>; this element is used to assign {@link #className} and {@link #methodName}.
		* Assigns <code>true</code> to {@link #isProgramEntryPoint} if and only if <code>methodName</code> equals <code>main</code>
		* and the <code>StackTraceElement</code> that <code>methodName</code> was taken from is the last stack trace element.
		* (If you only checked that <code>methodName</code> equals <code>main</code>, then the caller of this class
		* could also be merely a <code>main</code> that is being ultimately called by some other <code>main</code> which is the true program entry point.)
		* <p>
		* @throws IllegalStateException if some unexpected state is encountered
		*/
		private Caller() throws IllegalStateException {
			StackTraceElement[] traces = Thread.currentThread().getStackTrace();
			if (!traces[0].getClassName().equals(classNameThread)) throw new IllegalStateException("traces[0].getClassName() = " + traces[0].getClassName() + " !equals " + classNameThread);
			if (!traces[0].getMethodName().equals("getStackTrace")) throw new IllegalStateException("traces[0].getMethodName() = " + traces[0].getMethodName() + " !equals getStackTrace");
			int index = -1;
			for (int i = 1; i < traces.length; i++) {	// CRITICAL: start loop at 1, not 0, since have already checked the 0th element above
				String className = traces[i].getClassName();
				if (!className.equals(classNameCaller) && !className.equals(classNameExecute)) {
					index = i;
					break;
				}
			}
			if (index == -1) throw new IllegalStateException("failed to find the relevant StackTraceElement");
			
			className = traces[index].getClassName();
			methodName = traces[index].getMethodName();
			isProgramEntryPoint = (methodName.equals("main")) && (index == traces.length - 1);
		}
		
	}
	
	// -------------------- UnitTest (static inner class) --------------------
	
	/** See the Overview page of the project's javadocs for a general description of this unit test class. */
	public static class UnitTest {
		
		//@Test public void test_...
			// Nope, can't use JUnit to test, since do not want the JVM exit to occur in the middle of a test suite.
			// Instead, must resort to old fashioned main method testing:
		public static void main(final String[] args) throws Exception {
			
			Execute.thenExitIfEntryPoint( new Callable<String>() { public String call() {
				return "This is the result of Execute.thenExit(Callable), so you should see the JVM die after this";
			} } );
		}
		
		/** This private constructor suppresses the default (public) constructor, ensuring non-instantiability. */
		private UnitTest() {}
		
	}
	
}
