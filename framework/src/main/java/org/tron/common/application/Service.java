/*
 * java-tron is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * java-tron is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.tron.common.application;

import org.tron.common.parameter.CommonParameter;

public interface Service {

  void init();

  void init(CommonParameter parameter);

  /**
   * Start the service.
   * {@link Service#init(CommonParameter parameter) init(CommonParameter parameter)} must be called
   * before this method.
   */
  void start();

  void stop();

  void blockUntilShutdown();
}
