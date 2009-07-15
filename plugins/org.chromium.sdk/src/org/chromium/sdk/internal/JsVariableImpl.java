// Copyright (c) 2009 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.sdk.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;

import org.chromium.sdk.JsVariable;
import org.chromium.sdk.JsValue.Type;
import org.chromium.sdk.internal.DebugContextImpl.SendingType;
import org.chromium.sdk.internal.ValueMirror.PropertyReference;
import org.chromium.sdk.internal.tools.v8.V8Protocol;
import org.chromium.sdk.internal.tools.v8.V8ProtocolUtil;
import org.chromium.sdk.internal.tools.v8.request.DebuggerMessage;
import org.chromium.sdk.internal.tools.v8.request.DebuggerMessageFactory;
import org.json.simple.JSONObject;

/**
 * A generic implementation of the JsVariable interface.
 */
public class JsVariableImpl implements JsVariable {

  private static final String DOT = ".";

  private static final String OPEN_BRACKET = "[";

  private static final String CLOSE_BRACKET = "]";

  /**
   * The variable value data as reported by the JavaScript VM (is used to
   * construct the variable value.)
   */
  private final ValueMirror valueData;

  /** The call frame this variable belongs in. */
  private final CallFrameImpl callFrame;

  /** The fully qualified name of this variable. */
  private final String variableFqn;

  /** The lazily constructed value of this variable. */
  private JsValueImpl value;

  // The access is synchronized
  private boolean pendingReq = false;

  // Sentry to stop drilling in for props of type object.
  private boolean waitDrilling;

  protected volatile boolean failedResponse = false;

  /**
   * Constructs a variable contained in the given call frame with the given
   * value mirror.
   *
   * @param callFrame that owns this variable
   * @param valueData value data for this variable
   */
  JsVariableImpl(CallFrameImpl callFrame, ValueMirror valueData) {
    this(callFrame, valueData, null, false);
  }

  /**
   * Constructs a variable contained in the given call frame with the given
   * value mirror.
   *
   * @param callFrame that owns this variable
   * @param valueData for this variable
   * @param variableFqn the fully qualified name of this variable
   * @param waitDrilling whether to halt drilling in for any properties of type "object"
   */
  JsVariableImpl(
      CallFrameImpl callFrame, ValueMirror valueData, String variableFqn, boolean waitDrilling) {
    this.callFrame = callFrame;
    this.valueData = valueData;
    this.variableFqn = variableFqn;
    this.waitDrilling = waitDrilling;
  }

  /**
   * @return a [probably compound] JsValue corresponding to this variable.
   *         {@code null} if there was an error lazy-loading the value data.
   */
  public synchronized JsValueImpl getValue() {
    if (isFailedResponse()) {
      return null;
    }
    if (value == null) {
      Type type = this.valueData.getType();
      switch (type) {
        case TYPE_OBJECT:
          this.value = new JsObjectImpl(callFrame, getFullyQualifiedName(), this.valueData);
          break;
        case TYPE_ARRAY:
          this.value = new JsArrayImpl(callFrame, getFullyQualifiedName(), this.valueData);
          break;
        default:
          this.value = new JsValueImpl(this.valueData);
      }
    }
    return value;
  }

  public String getName() {
    String name = valueData.getName();
    if (JsonUtil.isInteger(name)) {
      // Fix array element indices
      name = OPEN_BRACKET + name + CLOSE_BRACKET;
    }
    return name;
  }

  public String getReferenceTypeName() {
    return valueData.getTypeAsString();
  }

  public boolean hasValueChanged() {
    return false; // we do not track values between suspended states
  }

  public synchronized void setValue(JsValueImpl value) {
    this.value = value;
  }

  public boolean isMutable() {
    return false; // TODO(apavlov): fix once V8 supports it
  }

  public boolean isReadable() {
    // TODO(apavlov): implement once the readability metadata are available
    return true;
  }

  public synchronized void setValue(String newValue, SetValueCallback callback) {
    // TODO(apavlov): currently V8 does not support it
    if (!isMutable()) {
      throw new UnsupportedOperationException();
    }
  }

  public Type getType() {
    return valueData.getType();
  }

  @Override
  public String toString() {
    StringBuilder result = new StringBuilder();
    result.append("[JsVariable: name=").append(getName()).append(",type=").append(getType())
        .append(",value=").append(getValue());
    result.append(']');
    return result.toString();
  }

  /**
   * Returns the call frame owning this variable.
   */
  protected CallFrameImpl getCallFrame() {
    return callFrame;
  }

  // Used for object properties filling
  public void setTypeValue(Type type, String val) {
    valueData.setType(type);

    Type existingType = valueData.getType();
    if (existingType == null) {
      valueData.setValue(val);
      setValue(new JsValueImpl(valueData));
      return;
    }
    switch (existingType) {
      case TYPE_NUMBER:
      case TYPE_STRING:
      case TYPE_BOOLEAN:
      case TYPE_UNDEFINED:
      case TYPE_NULL:
      case TYPE_DATE:
        valueData.setValue(val);
        setValue(new JsValueImpl(valueData));
        break;
      case TYPE_OBJECT:
        // You cannot set an object value
        break;
    }
  }

  /**
   * Resolves property references and sets the class name of an Object variable.
   *
   * @param className of this object
   * @param properties of the Object variable
   */
  public synchronized void setProperties(String className, PropertyReference[] properties) {
    if (properties != null) {
      valueData.setProperties(className, properties);
    }
  }

  public ValueMirror getMirror() {
    return valueData;
  }

  public String getFullyQualifiedName() {
    return variableFqn != null
        ? variableFqn
        : getName();
  }

  synchronized boolean isWaitDrilling() {
    return waitDrilling;
  }

  synchronized void resetDrilling() {
    waitDrilling = false;
  }

  synchronized void setPendingReq() {
    pendingReq = true;
  }

  synchronized boolean isPendingReq() {
    return pendingReq;
  }

  synchronized void resetPending() {
    pendingReq = false;
  }

  // Exposed as "package local" for testing.
  /* package local */void ensureProperties(PropertyReference[] properties) {
    final HandleManager handleManager = getHandleManager();
    // Use linked map to preserve the original (somewhat alphabetical)
    // properties order.
    final Map<JsVariableImpl, Long> variableToRef = new LinkedHashMap<JsVariableImpl, Long>();
    final Collection<Long> handlesToRequest = new HashSet<Long>(properties.length);

    for (PropertyReference prop : properties) {
      String propName = prop.getName();
      if (propName.length() == 0) {
        // Do not provide a synthetic "hidden properties" property.
        continue;
      }
      ValueMirror mirror = new ValueMirror(propName, prop.getRef());
      String fqn;

      if (JsonUtil.isInteger(propName)) {
        fqn = getFullyQualifiedName() + OPEN_BRACKET + propName + CLOSE_BRACKET;
      } else {
        if (propName.startsWith(DOT)) {
          // ".arguments" is not legal
          continue;
        }
        fqn = getFullyQualifiedName() + DOT + propName;
      }

      JsVariableImpl variable = new JsVariableImpl(callFrame, mirror, fqn, true);
      Long longRef = Long.valueOf(prop.getRef());
      JSONObject handle = handleManager.getHandle(longRef);
      if (handle != null) {
        // do not re-request from V8
        fillVariable(variable, handle);
      } else {
        handlesToRequest.add(longRef);
      }
      variableToRef.put(variable, longRef);
    }

    final Collection<JsVariableImpl> vars = variableToRef.keySet();

    synchronized (this) {
      this.value = (valueData.getType() == Type.TYPE_OBJECT)
          ? new JsObjectImpl(getCallFrame(), this.valueData, vars)
          : new JsArrayImpl(getCallFrame(), this.valueData, vars);
    }
    DebuggerMessage message = DebuggerMessageFactory.lookup(
        new ArrayList<Long>(handlesToRequest), true, getCallFrame().getToken());
    Exception ex = getCallFrame().getDebugContext().sendMessage(
        SendingType.SYNC,
        message,
        new BrowserTabImpl.V8HandlerCallback() {
          public void messageReceived(JSONObject response) {
            if (!fillVariablesFromLookupReply(handleManager, vars, variableToRef, response)) {
              setFailedResponse();
            }
          }

          public void failure(String message) {
            setFailedResponse();
          }
        });
    if (ex != null) {
      setFailedResponse();
    }
  }

  protected void setFailedResponse() {
    this.failedResponse = true;
  }

  protected boolean isFailedResponse() {
    return failedResponse;
  }

  private HandleManager getHandleManager() {
    return getCallFrame().getDebugContext().getHandleManager();
  }

  /**
   * @param vars all the variables for the properties
   * @param variableToRef variable to ref map
   * @param reply with the requested handles (some of {@code vars} may be
   *        missing if they were known at the moment of ensuring the properties)
   */
  static boolean fillVariablesFromLookupReply(HandleManager handleManager,
      Collection<JsVariableImpl> vars, Map<JsVariableImpl, Long> variableToRef, JSONObject reply) {
    if (!JsonUtil.isSuccessful(reply)) {
      return false;
    }
    JSONObject body = JsonUtil.getBody(reply);
    for (JsVariableImpl var : vars) {
      Long ref = variableToRef.get(var);
      JSONObject object = JsonUtil.getAsJSON(body, String.valueOf(ref));
      if (object != null) {
        handleManager.put(ref, object);
        fillVariable(var, JsonUtil.getAsJSON(body, String.valueOf(variableToRef.get(var))));
      }
    }
    return true;
  }

  /**
   * Fills in a variable from a handle object.
   * @param variable to fill in
   * @param handleObject to get the data from
   */
  static void fillVariable(JsVariableImpl variable, JSONObject handleObject) {
    String typeString = null;
    String valueString = null;
    if (handleObject != null) {
      typeString = JsonUtil.getAsString(handleObject, V8Protocol.REF_TYPE);
      valueString = JsonUtil.getAsString(handleObject, V8Protocol.REF_TEXT);
    }
    if ("error".equals(typeString)) {
      // Report the JS VM error.
      if (valueString == null) {
        valueString = "An error occurred while retrieving the value.";
      }
      variable.setTypeValue(Type.TYPE_STRING, valueString);
      variable.resetPending();
      return;
    }
    Type type =
        JsDataTypeUtil.fromJsonTypeAndClassName(typeString, JsonUtil.getAsString(handleObject,
            V8Protocol.REF_CLASSNAME));
    if (Type.isObjectType(type)) {
      if (!variable.isPendingReq()) {
        if (!variable.isWaitDrilling()) {
          PropertyReference[] propertyRefs = V8ProtocolUtil.extractObjectProperties(handleObject);
          variable.setProperties(JsonUtil.getAsString(handleObject, V8Protocol.REF_CLASSNAME),
              propertyRefs);
        }
        variable.resetDrilling();
      }
    } else if (valueString != null && type != null) {
      variable.setTypeValue(type, valueString);
      variable.resetPending();
    } else {
      variable.setTypeValue(Type.TYPE_STRING, "<Error>");
      variable.resetPending();
    }
  }
}