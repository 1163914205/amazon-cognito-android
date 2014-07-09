/**
 * Copyright 2013-2014 Amazon.com, 
 * Inc. or its affiliates. All Rights Reserved.
 * 
 * Licensed under the Amazon Software License (the "License"). 
 * You may not use this file except in compliance with the 
 * License. A copy of the License is located at
 * 
 *     http://aws.amazon.com/asl/
 * 
 * or in the "license" file accompanying this file. This file is 
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR 
 * CONDITIONS OF ANY KIND, express or implied. See the License 
 * for the specific language governing permissions and 
 * limitations under the License.
 */

package com.amazonaws.android.cognito.exceptions;

/**
 * This exception is thrown when an error occurs during an data storage
 * operation.
 */
public class DataStorageException extends RuntimeException {

    private static final long serialVersionUID = -6906342391685175623L;

    public DataStorageException() {
        super();
    }

    public DataStorageException(String detailMessage, Throwable throwable) {
        super(detailMessage, throwable);
    }

    public DataStorageException(String detailMessage) {
        super(detailMessage);
    }

    public DataStorageException(Throwable throwable) {
        super(throwable);
    }

}
