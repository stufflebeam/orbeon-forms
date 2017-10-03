/**
 * Copyright (C) 2017 Orbeon, Inc.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU Lesser General Public License as published by the Free Software Foundation; either version
 * 2.1 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
package org.orbeon.builder

import org.orbeon.builder.BlockCache.Block
import org.orbeon.jquery.Offset
import org.orbeon.xforms._
import org.orbeon.xforms.facade.Events
import org.scalajs.dom.{document, window}
import org.scalajs.jquery.{JQuery, JQueryEventObject}

import scala.scalajs.js
import scala.scalajs.js.JSConverters._

object Position {

  // Keeps track of pointer position
  var pointerPos: Offset = Offset(0, 0)

  $(document).on("mousemove", (event: JQueryEventObject) ⇒ {
    pointerPos =
      Offset(
        left = event.pageX,
        top  = event.pageY
    )
  })

  // How much we need to add to offset to account for the form having been scrolled
  def scrollTop() : Double  = $(".fb-main").scrollTop ()
  def scrollLeft(): Double  = $(".fb-main").scrollLeft()

  // Gets an element offset, normalizing for scrolling, so the offset can be stored in a cache
  def adjustedOffset(el: JQuery): Offset = {
    val rawOffset = Offset(el)
    Offset(
      left = rawOffset.left,
      top  = rawOffset.top + scrollTop()
    )
  }

  // Calls listener when what is under the pointer has potentially changed
  def onUnderPointerChange(fn: ⇒ Unit): Unit = {
    $(document).on("mousemove", fn _)
    // Resizing the window might change what is under the pointer the last time we saw it in the window
    $(window).on("resize", fn _)
    Events.ajaxResponseProcessedEvent.subscribe(fn _)
  }

  // Call listener when anything on the page that could change element positions happened
  def onOffsetMayHaveChanged(fn: js.Function): Unit = {
      // After the form is first shown
      Events.orbeonLoadedEvent.subscribe(fn)
      // After an Ajax response, as it might have changed the DOM
      Events.ajaxResponseProcessedEvent.subscribe(fn)
      $(window).on("resize", fn)
  }

  // Finds the container, if any, based on a vertical position
  def findInCache(
    containerCache : js.Array[Block],
    top            : Double,
    left           : Double
  )                : js.UndefOr[Block] = {

    containerCache.find { container ⇒
      // Rounding when comparing as the offset of an element isn't always exactly the same as the offset it was set to
      val horizontalPosInside = Math.floor(container.left) <= left && left <= Math.ceil(container.left + container.width)
      val verticalPosInside   = Math.floor(container.top ) <= top  && top  <= Math.ceil(container.top  + container.height)
      horizontalPosInside && verticalPosInside
    }.orUndefined
  }

  // Container is either a section or grid; calls listeners passing old/new container
  def currentContainerChanged(
    containerCache : js.Array[Block],
    wasCurrent     : (Block) ⇒ Unit,
    becomesCurrent : (Block) ⇒ Unit)
                   : Unit = {

    val notifyChange = notifyOnChange(wasCurrent, becomesCurrent)
    onUnderPointerChange {
      scala.scalajs.js.Dynamic.global.console.log("check new container")
      val top  = pointerPos.top  + Position.scrollTop()
      val left = pointerPos.left + Position.scrollLeft()
      val newContainer = findInCache(containerCache, top, left)
      notifyChange(newContainer.toOption)
    }
  }

  // Returns a function, which is expected to be called every time the value changes passing the new value, and which
  // will when appropriate notify the listeners `was` and `becomes` of the old and new value
  // TODO: replace `Any` by `Unit` once callers are all in Scala
  def notifyOnChange[T](
    was     : (Block) ⇒ Unit,
    becomes : (Block) ⇒ Unit)
            : (Option[Block]) ⇒ Unit = {

    var currentBlockOpt: Option[Block] = None

    (newBlockOpt: Option[Block]) ⇒ {
      newBlockOpt match {
        case Some(newBlock) ⇒
          val doNotify =
            currentBlockOpt match {
              case None ⇒ true
              case Some(currentBlock) ⇒
                // Typically after an Ajax request, maybe a column/row was added/removed, so we might consequently
                // need to update the icon position
                ! newBlock.el.is(currentBlock.el) ||
                // The elements could be the same, but their position could have changed, in which case want to
                // reposition relative icons, so we don't consider the value to be the "same"
                newBlock.left != currentBlock.left ||
                newBlock.top != currentBlock.top
            }
          if (doNotify) {
            currentBlockOpt.foreach(was)
            currentBlockOpt = newBlockOpt
            becomes(newBlock)
          }
        case None ⇒
          currentBlockOpt.foreach(was)
          currentBlockOpt = None
      }
    }
  }

}
