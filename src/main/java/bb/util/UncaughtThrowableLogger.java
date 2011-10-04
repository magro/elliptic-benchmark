/*
Copyright � 2008 Brent Boyer

This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the Lesser GNU General Public License for more details.

You should have received a copy of the Lesser GNU General Public License along with this program (see the license directory in this project).  If not, see <http://www.gnu.org/licenses/>.
*/

package bb.util;

import static bb.util.StringUtil.newline;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
* Logs all otherwise uncaught Throwables to a Logger.
* <p>
* This class is multithread safe: the types of its state are individually multithread safe, and are used such that only their individual multithread safety matters.
* <p>
* @author Brent Boyer
* @see <a href="http://java.sun.com/developer/JDCTechTips/2006/tt0211.html#2">Catching Uncaught Exceptions</a>
*/
public class UncaughtThrowableLogger implements Thread.UncaughtExceptionHandler {
	
	// -------------------- fields --------------------
	
	/**
	* Logger where all Throwables will get logged to.
	* <p>
	* Contract: this field is never null.
	*/
	private final Logger logger;
	
	// -------------------- constructor --------------------
	
	/** Convenience constructor that simply calls <code>{@link #UncaughtThrowableLogger(Logger) this}( {@link LogUtil#getLogger2} )</code>. */
	public UncaughtThrowableLogger() {
		this( Logger.getAnonymousLogger() );
	}
	
	/**
	* Fundamental constructor.
	* Uses logger to record all otherwise uncaught Throwables.
	* <p>
	* @throws IllegalArgumentException if logger == null
	*/
	public UncaughtThrowableLogger(Logger logger) throws IllegalArgumentException {
		Check.arg().notNull(logger);
		
		this.logger = logger;
	}
	
	// -------------------- Thread.UncaughtExceptionHandler api --------------------
	
	public void uncaughtException(Thread thread, Throwable throwable) {
		StringBuilder sb = new StringBuilder(256);
		sb.append("AN UNCAUGHT Throwable HAS BEEN DETECTED").append(newline);
		
		String threadInfo = (thread != null) ? ThreadUtil.toString(thread) : "UNKNOWN (null was supplied)" + newline;
		sb.append("Thread reporting the uncaught Throwable: ").append(threadInfo);
		
		String throwableInfo = (throwable != null) ? ThrowableUtil.toString(throwable) : "UNKNOWN (null was supplied)" + newline;
		sb.append("Uncaught Throwable: ").append(throwableInfo);
		
		String msg = sb.toString();
		
		logger.logp(Level.SEVERE, "UncaughtThrowableLogger", "uncaughtException", msg);
	}
	
}
